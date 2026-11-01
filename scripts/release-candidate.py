#!/usr/bin/env python3
"""Retain the exact tested executable/image. No registry or release mutations."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

VERSION = '0.0.1'
REPOSITORY = 'busystack/caldav-server'
ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def validate(directory):
    directory = Path(directory)
    candidate = json.loads((directory / 'candidate.json').read_text())
    assert candidate['repository'] == REPOSITORY and candidate['version'] == VERSION
    assert candidate['platform'] == 'linux/amd64'
    assert len(candidate['sourceCommit']) == 40 and all(c in '0123456789abcdef' for c in candidate['sourceCommit'])
    assert candidate['files'] and set(candidate['files']) == {f'caldav-server-{VERSION}.jar', 'container.tar.gz', 'sbom.cdx.json'}
    for name, expected in candidate['files'].items():
        assert Path(name).name == name and sha256(directory / name) == expected, 'Candidate checksum mismatch: ' + name
    with zipfile.ZipFile(directory / f'caldav-server-{VERSION}.jar') as jar:
        assert f'Implementation-Version: {VERSION}\n' in jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n', '\n')
        assert jar.read('META-INF/sbom/application.cdx.json') == (directory / 'sbom.cdx.json').read_bytes()
    return candidate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--image', default='busystack-caldav:ci')
    args = parser.parse_args()
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=False)
    revision = os.environ['GITHUB_SHA']
    assert os.environ['GITHUB_REPOSITORY'] == REPOSITORY
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == revision
    config = json.loads(subprocess.check_output(['docker', 'image', 'inspect', args.image]))[0]
    assert config['Os'] == 'linux' and config['Architecture'] == 'amd64'
    assert config['Config']['Labels']['org.opencontainers.image.revision'] == revision
    assert config['Config']['Labels']['org.opencontainers.image.version'] == VERSION
    jar = destination / f'caldav-server-{VERSION}.jar'
    shutil.copyfile(ROOT / 'target' / jar.name, jar)
    with zipfile.ZipFile(jar) as archive:
        (destination / 'sbom.cdx.json').write_bytes(archive.read('META-INF/sbom/application.cdx.json'))
    image_jar = subprocess.check_output(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', args.image, '/opt/busystack/application.jar'], text=True).split()[0]
    assert image_jar == sha256(jar)
    archive = destination / 'container.tar'
    subprocess.run(['docker', 'image', 'save', '--output', str(archive), args.image], check=True)
    subprocess.run(['gzip', '-n', str(archive)], check=True)
    files = {path.name: sha256(path) for path in destination.iterdir()}
    write_json(destination / 'candidate.json', {
        'schemaVersion': 1, 'repository': REPOSITORY, 'version': VERSION,
        'sourceCommit': revision, 'ciRun': int(os.environ['GITHUB_RUN_ID']),
        'ciAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']), 'workflow': '.github/workflows/ci.yml',
        'platform': 'linux/amd64', 'imageId': config['Id'], 'imageTag': args.image, 'files': files,
    })
    validate(destination)


if __name__ == '__main__':
    main()
