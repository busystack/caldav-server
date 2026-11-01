#!/usr/bin/env python3
"""Generate digest-pinned, reproducible deployment assets from a verified candidate."""
import argparse
import gzip
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
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
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    version = record['version']
    image = f'ghcr.io/{candidate.REPOSITORY}:{version}@{digest}'
    compose = (ROOT / 'compose.yaml').read_text()
    assert compose.count('    build: .\n') == 1
    compose = compose.replace('    build: .\n', '').replace(f'    image: busystack-caldav:{version}\n', f'    image: {image}\n    platform: linux/amd64\n')
    assert 'build:' not in compose and image in compose
    identity = {**record, 'registryDigest': digest, 'image': image}
    identity.pop('files')
    files = {'compose.yaml': compose.encode(), 'SOURCE.json': (json.dumps(identity, indent=2, sort_keys=True) + '\n').encode()}
    for path in (ROOT / 'deployment').iterdir():
        files[path.name] = path.read_bytes()
    for name in ('init-db.sh', 'backup.sh', 'restore.sh'):
        files['scripts/' + name] = (ROOT / 'scripts' / name).read_bytes()
    # All linked operational documents remain available offline. No Java/source tree.
    for path in (ROOT / 'docs').rglob('*.md'):
        if 'development' not in path.parts:
            files[str(path.relative_to(ROOT))] = path.read_bytes()
    notices = {name: (ROOT / name).read_bytes() for name in ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES')}
    for base in ('licenses', 'src/main/resources/META-INF/licenses'):
        for path in (ROOT / base).iterdir():
            notices['licenses/' + path.name] = path.read_bytes()
    files.update(notices)
    bundle_name = f'caldav-server-{version}-deploy.tar.gz'
    (output / bundle_name).write_bytes(tar_bytes(files, f'caldav-server-{version}-deploy/'))
    (output / 'notices.tar.gz').write_bytes(tar_bytes(notices))
    for name in (f'caldav-server-{version}.jar', 'sbom.cdx.json'):
        shutil.copyfile(Path(source) / name, output / name)
    assets = {path.name: candidate.sha256(path) for path in output.iterdir()}
    manifest = {
        'schemaVersion': 1, 'version': version, 'sourceCommit': record['sourceCommit'],
        'repository': 'https://github.com/' + candidate.REPOSITORY,
        'verification': {'run': record['ciRun'], 'attempt': record['ciAttempt'],
                         'url': f"https://github.com/{candidate.REPOSITORY}/actions/runs/{record['ciRun']}",
                         'workflow': record['workflow'], 'requiredJobs': ['release-tools', 'application', 'dependency-audit']},
        'container': {'image': image, 'registryManifestDigest': digest, 'localTestedImageId': record['imageId'], 'platform': 'linux/amd64'},
        'jar': {'name': f'caldav-server-{version}.jar', 'sha256': assets[f'caldav-server-{version}.jar']},
        'deployment': {'name': bundle_name, 'sha256': assets[bundle_name]},
        'assets': assets,
        'qualificationEvidence': [f"https://github.com/{candidate.REPOSITORY}/actions/runs/{record['ciRun']}",
                                  f"https://github.com/{candidate.REPOSITORY}/blob/{record['sourceCommit']}/qualification/evidence/releases/0.0.1/official-dependencies/verification.json"],
        'release': f'https://github.com/{candidate.REPOSITORY}/releases/tag/v{version}',
    }
    candidate.write_json(output / 'release-manifest.json', manifest)
    sums = {**assets, 'release-manifest.json': candidate.sha256(output / 'release-manifest.json')}
    (output / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(sums.items())))
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('--digest', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    build(args.candidate, args.digest, args.output)
