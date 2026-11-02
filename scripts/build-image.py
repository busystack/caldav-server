#!/usr/bin/env python3
"""Build and verify the exact Maven JAR and OCI identity; optionally run development Compose."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_version as versions

ROOT = versions.ROOT


def inspect_jar(path, version):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        assert len(names) == len(set(names)), 'Duplicate JAR entries'
        manifest = dict(line.split(': ', 1) for line in jar.read('META-INF/MANIFEST.MF').decode().splitlines() if ': ' in line)
        assert manifest.get('Implementation-Version') == version, 'JAR implementation version differs from Maven'
        coordinates = dict(line.split('=', 1) for line in jar.read('META-INF/maven/org.busystack/caldav-server/pom.properties').decode().splitlines() if '=' in line and not line.startswith('#'))
        assert coordinates == {'groupId': 'org.busystack', 'artifactId': 'caldav-server', 'version': version}, 'JAR Maven coordinates differ from qualified project'
        sbom = jar.read('META-INF/sbom/application.cdx.json')
        component = json.loads(sbom)['metadata']['component']
        assert component['version'] == version, 'Project SBOM version differs from Maven'
        assert component['name'] == 'caldav-server' and component['group'] == 'org.busystack', 'Project SBOM coordinates differ from Maven'
    return hashlib.sha256(Path(path).read_bytes()).hexdigest(), sbom


def identity(image, version, revision, jar_sha):
    config = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
    labels = config['Config']['Labels'] or {}
    assert labels.get('org.opencontainers.image.version') == version, 'Image version differs from Maven'
    assert labels.get('org.opencontainers.image.revision') == revision, 'Image source revision differs from qualified source'
    assert labels.get('org.opencontainers.image.source') == 'https://github.com/' + versions.REPOSITORY
    assert labels.get('org.opencontainers.image.licenses') == 'Apache-2.0'
    assert config['Os'] == 'linux' and config['Architecture'] == 'amd64', 'Expected qualified Linux AMD64 image'
    checksum = subprocess.check_output(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', image, '/opt/busystack/application.jar'], text=True).split()[0]
    assert checksum == jar_sha, 'Container does not contain the exact expected JAR'
    return config


def compose_environment(image, context, version, revision):
    versions.stable(version)
    assert len(revision) == 40 and all(c in '0123456789abcdef' for c in revision), 'Invalid source revision'
    return {'BUSYSTACK_APPLICATION_IMAGE': image, 'BUSYSTACK_BUILD_CONTEXT': str(context),
            'BUSYSTACK_BUILD_VERSION': version, 'BUSYSTACK_SOURCE_REVISION': revision}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', help='Optional local image name; Maven still supplies the application version')
    parser.add_argument('--jar', help='Use an already tested JAR instead of running clean package')
    parser.add_argument('--compose', nargs=argparse.REMAINDER, help='Compose arguments, for example up -d --no-build --wait')
    args = parser.parse_args()
    assert not subprocess.check_output(['git', 'status', '--porcelain=v1', '--untracked-files=all'], cwd=ROOT), 'Commit source changes before building an image with an exact Git revision'
    revision = versions.revision(root=ROOT)
    version = versions.resolve(ROOT, source=revision)
    if not args.jar:
        subprocess.run([str(ROOT / 'mvnw'), '--batch-mode', '--no-transfer-progress', '-DskipTests', 'clean', 'package'], cwd=ROOT, check=True)
    jar = Path(args.jar).resolve() if args.jar else ROOT / 'target' / versions.jar_name(version)
    assert jar.name == versions.jar_name(version), 'Unexpected JAR filename'
    assert sorted(p.name for p in jar.parent.glob('*.jar')) == [jar.name], 'Missing or ambiguous packaged JARs; clean the build directory'
    jar_sha, sbom = inspect_jar(jar, version)
    context = ROOT / 'target/docker-context'
    if context.exists():
        shutil.rmtree(context)
    inputs = context / '.image-input'
    inputs.mkdir(parents=True)
    shutil.copyfile(ROOT / 'Dockerfile', context / 'Dockerfile')
    shutil.copyfile(jar, inputs / 'application.jar')
    (inputs / 'runtime-dependency-inventory.json').write_bytes(sbom)
    for name in ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES'):
        shutil.copyfile(ROOT / name, inputs / name)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('META-INF/licenses/') and not name.endswith('/'):
                path = inputs / 'third-party' / name.removeprefix('META-INF/licenses/')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
    (inputs / 'version').write_text(version + '\n')
    (inputs / 'revision').write_text(revision + '\n')
    (inputs / 'SHA256SUMS').write_text(jar_sha + '  application.jar\n')
    image = args.image or 'busystack-caldav:' + version
    subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '--build-arg', 'APPLICATION_VERSION=' + version,
                    '--build-arg', 'SOURCE_REVISION=' + revision, '--tag', image, str(context)], check=True)
    config = identity(image, version, revision, jar_sha)
    result = {'version': version, 'sourceCommit': revision, 'jarSha256': jar_sha, 'imageId': config['Id'], 'image': image}
    (ROOT / 'target/image-build.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    if args.compose is not None:
        assert args.compose, 'Supply Compose arguments after --compose'
        environment = os.environ | compose_environment(image, context, version, revision)
        subprocess.run(['docker', 'compose', *args.compose], cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    main()
