#!/usr/bin/env python3
"""Generate digest-pinned, reproducible deployment assets from a verified candidate."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_version as versions

ROOT = Path(__file__).resolve().parents[1]
OPERATOR_DOCUMENTS = (
    'docs/operations.md',
    'docs/account-security.md',
    'docs/calendar-semantics.md',
    'docs/groups.md',
    'docs/keycloak.md',
    'docs/oauth.md',
    'docs/observance-until-compatibility.md',
    'docs/passkeys-and-mfa.md',
    'docs/performance.md',
    'docs/third-party-licenses.md',
    'docs/versions.md',
    'docs/adr/0004-resumable-synchronization.md',
)
spec = importlib.util.spec_from_file_location('candidate', ROOT / 'scripts/release-candidate.py')
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)


def tar_bytes(files, prefix=''):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as archive:
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(prefix + name)
            entry.size = len(data)
            entry.mtime = 0
            entry.mode = 0o755 if name.endswith('.sh') else 0o644
            archive.addfile(entry, io.BytesIO(data))
    return gzip.compress(buffer.getvalue(), mtime=0)


def build(source, digest, output):
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'Expected registry manifest digest'
    record = candidate.validate(source)
    qualified = candidate.snapshot(source, record)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    version = record['version']
    image = f'ghcr.io/{candidate.REPOSITORY}:{version}@{digest}'
    compose = qualified.read('compose.yaml').decode()
    build_start = compose.index('    build:\n')
    image_start = compose.index('    image: ${BUSYSTACK_APPLICATION_IMAGE:', build_start)
    image_end = compose.index('\n', image_start) + 1
    compose = compose[:build_start] + f'    image: {image}\n    platform: linux/amd64\n' + compose[image_end:]
    assert 'build:' not in compose and 'BUSYSTACK_BUILD_' not in compose and image in compose
    identity = {**record, 'registryDigest': digest, 'image': image}
    identity.pop('files')
    files = {'compose.yaml': compose.encode(), 'SOURCE.json': (json.dumps(identity, indent=2, sort_keys=True) + '\n').encode()}
    used = {'compose.yaml'}
    def content(name, render=False):
        used.add(name)
        data = qualified.read(name)
        return data.replace(b'@VERSION@', version.encode()) if render else data
    for name in qualified.under('deployment'):
        files[name.removeprefix('deployment/')] = content(name, render=True)
    for name in ('init-db.sh', 'backup.sh', 'restore.sh'):
        files['scripts/' + name] = content('scripts/' + name)
    for name in (*OPERATOR_DOCUMENTS, f'docs/releases/{version}.md'):
        files[name] = content(name, render=True)
    receipt = versions.receipt_path(version)
    files[receipt] = content(receipt)
    notices = {name: content(name) for name in ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES')}
    for base in ('licenses', 'src/main/resources/META-INF/licenses'):
        for name in qualified.under(base):
            destination = 'licenses/' + name.removeprefix(base + '/')
            data = content(name)
            assert destination not in notices or notices[destination] == data, 'Conflicting license input'
            notices[destination] = data
    notices['runtime-dependency-inventory.json'] = (Path(source) / 'sbom.cdx.json').read_bytes()
    files.update(notices)
    bundle_name = f'caldav-server-{version}-deploy.tar.gz'
    (output / bundle_name).write_bytes(tar_bytes(files, f'caldav-server-{version}-deploy/'))
    (output / 'notices.tar.gz').write_bytes(tar_bytes(notices))
    for name in (f'caldav-server-{version}.jar', 'sbom.cdx.json'):
        shutil.copyfile(Path(source) / name, output / name)
    assets = {path.name: candidate.sha256(path) for path in output.iterdir()}
    manifest = {
        'schemaVersion': 2, 'version': version, 'sourceCommit': record['sourceCommit'],
        'sourceTree': record['sourceTree'], 'sourceSnapshotSha256': record['files']['source.tar.gz'],
        'sourceInputs': {name: hashlib.sha256(qualified.read(name)).hexdigest() for name in sorted(used)},
        'deploymentFiles': {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())},
        'repository': 'https://github.com/' + candidate.REPOSITORY,
        'verification': {'run': record['ciRun'], 'attempt': record['ciAttempt'],
                         'url': f"https://github.com/{candidate.REPOSITORY}/actions/runs/{record['ciRun']}",
                         'workflow': record['workflow'], 'requiredJobs': ['release-tools', 'application', 'dependency-audit']},
        'container': {'image': image, 'registryManifestDigest': digest, 'localTestedImageId': record['imageId'], 'testedArchiveSha256': record['files']['container.tar.gz'], 'platform': 'linux/amd64'},
        'jar': {'name': f'caldav-server-{version}.jar', 'sha256': assets[f'caldav-server-{version}.jar']},
        'deployment': {'name': bundle_name, 'sha256': assets[bundle_name]},
        'assets': assets,
        'qualificationEvidence': [f"https://github.com/{candidate.REPOSITORY}/actions/runs/{record['ciRun']}",
                                  f"https://github.com/{candidate.REPOSITORY}/blob/{record['sourceCommit']}/{versions.receipt_path(version)}"],
        'stagingRun': os.environ.get('GITHUB_RUN_ID'),
        'stagingEvidence': 'https://github.com/' + candidate.REPOSITORY + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', 'local-fixture'),
        'release': f'https://github.com/{candidate.REPOSITORY}/releases/tag/v{version}',
    }
    candidate.write_json(output / 'release-manifest.json', manifest)
    sums = {**assets, 'release-manifest.json': candidate.sha256(output / 'release-manifest.json')}
    (output / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(sums.items())))
    return manifest


def validate_assets(directory, version=None, source_sha=None):
    directory = Path(directory)
    manifest = json.loads((directory / 'release-manifest.json').read_text())
    selected = versions.stable(manifest['version'])
    assert version is None or selected == version, 'Wrong asset version'
    assert source_sha is None or manifest['sourceCommit'] == source_sha, 'Wrong asset source'
    expected = {versions.jar_name(selected), versions.deployment_name(selected), 'sbom.cdx.json', 'notices.tar.gz'}
    assert set(manifest['assets']) == expected, 'Missing or unexpected release assets'
    assert {p.name for p in directory.iterdir()} == expected | {'release-manifest.json', 'SHA256SUMS'}, 'Unexpected asset files'
    for name, checksum in manifest['assets'].items():
        assert candidate.sha256(directory / name) == checksum, 'Release asset checksum mismatch: ' + name
    sums = {**manifest['assets'], 'release-manifest.json': candidate.sha256(directory / 'release-manifest.json')}
    assert (directory / 'SHA256SUMS').read_text() == ''.join(f'{sha}  {name}\n' for name, sha in sorted(sums.items())), 'Incorrect release checksums'
    assert manifest['jar'] == {'name': versions.jar_name(selected), 'sha256': sums[versions.jar_name(selected)]}
    assert manifest['deployment'] == {'name': versions.deployment_name(selected), 'sha256': sums[versions.deployment_name(selected)]}
    _, sbom = candidate.builder.inspect_jar(directory / versions.jar_name(selected), selected)
    assert sbom == (directory / 'sbom.cdx.json').read_bytes()
    digest = manifest['container']['registryManifestDigest']
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', digest)
    assert manifest['container']['image'] == f'ghcr.io/{candidate.REPOSITORY}:{selected}@{digest}'
    with tarfile.open(directory / versions.deployment_name(selected)) as archive:
        prefix = 'caldav-server-' + selected + '-deploy/'
        members = archive.getmembers()
        assert all(p.isfile() and p.name.startswith(prefix) and '..' not in Path(p.name).parts for p in members)
        contents = {p.name.removeprefix(prefix): archive.extractfile(p).read() for p in members}
        assert len(contents) == len(members), 'Duplicate deployment entries'
    assert {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()} == manifest['deploymentFiles'], 'Deployment content mismatch'
    assert 'build:' not in contents['compose.yaml'].decode() and manifest['container']['image'].encode() in contents['compose.yaml']
    with tarfile.open(directory / 'notices.tar.gz') as archive:
        for member in archive:
            assert member.isfile() and member.name in contents, 'Unexpected notice entry'
            assert archive.extractfile(member).read() == contents[member.name], 'Notice/deployment mismatch'
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--digest', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    build(args.candidate, args.digest, args.output)
