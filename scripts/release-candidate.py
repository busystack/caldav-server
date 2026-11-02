#!/usr/bin/env python3
"""Retain the exact tested executable, image and qualified source snapshot."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_version as versions
import release_source
import release_image

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = versions.REPOSITORY
spec = importlib.util.spec_from_file_location('image_builder', ROOT / 'scripts/build-image.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def snapshot(directory, record):
    return release_source.Snapshot(record['sourceCommit'], ROOT, Path(directory) / 'source.tar.gz')


def validate(directory, requested=None, source_sha=None):
    directory = Path(directory)
    record = json.loads((directory / 'candidate.json').read_text())
    assert record['repository'] == REPOSITORY
    version = versions.resolve(ROOT, source=record['sourceCommit'], requested=requested or record['version'])
    assert record['version'] == version, 'Candidate version differs from qualified Maven version'
    assert source_sha is None or record['sourceCommit'] == source_sha, 'Wrong candidate source commit'
    assert record['platform'] == 'linux/amd64'
    expected = {versions.jar_name(version), 'container.tar.gz', 'sbom.cdx.json', 'source.tar.gz'}
    assert set(record['files']) == expected, 'Missing or unexpected candidate files'
    assert {p.name for p in directory.iterdir()} == expected | {'candidate.json'}, 'Unexpected candidate contents'
    for name, checksum in record['files'].items():
        assert Path(name).name == name and sha256(directory / name) == checksum, 'Candidate checksum mismatch: ' + name
    source = snapshot(directory, record)
    assert source.version == version
    assert record['sourceTree'] == subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', record['sourceCommit'] + '^{tree}'], text=True).strip()
    _, sbom = builder.inspect_jar(directory / versions.jar_name(version), version)
    assert sbom == (directory / 'sbom.cdx.json').read_bytes(), 'Embedded and staged SBOM differ'
    digest, _ = release_image.saved_identity(directory / 'container.tar.gz', version, record['sourceCommit'])
    assert record.get('imageConfigDigest', digest) == digest, 'Saved image configuration checksum mismatch'
    return {**record, 'imageConfigDigest': digest}


def load_image(directory, record):
    actual, digest = release_image.load(Path(directory) / 'container.tar.gz', record['version'], record['sourceCommit'],
                                       record['files'][versions.jar_name(record['version'])], builder.identity)
    assert digest == record['imageConfigDigest']
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--image', default='busystack-caldav:ci')
    args = parser.parse_args()
    revision = versions.revision(root=ROOT)
    version = versions.resolve(ROOT, source=revision)
    assert os.environ['GITHUB_REPOSITORY'] == REPOSITORY and os.environ['GITHUB_SHA'] == revision
    assert not subprocess.check_output(['git', 'status', '--porcelain=v1', '--untracked-files=all'], cwd=ROOT), 'Dirty candidate source'
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=False)
    jar = destination / versions.jar_name(version)
    expected = ROOT / 'target' / jar.name
    assert sorted(p.name for p in expected.parent.glob('*.jar')) == [jar.name], 'Ambiguous build artifacts'
    checksum, sbom = builder.inspect_jar(expected, version)
    config = builder.identity(args.image, version, revision, checksum)
    shutil.copyfile(expected, jar)
    (destination / 'sbom.cdx.json').write_bytes(sbom)
    release_source.retain(revision, destination / 'source.tar.gz', ROOT)
    archive = destination / 'container.tar'
    subprocess.run(['docker', 'image', 'save', '--output', str(archive), config['Id']], check=True)
    subprocess.run(['gzip', '-n', str(archive)], check=True)
    image_config, _ = release_image.saved_identity(destination / 'container.tar.gz', version, revision)
    write_json(destination / 'candidate.json', {
        'schemaVersion': 2, 'repository': REPOSITORY, 'version': version,
        'sourceCommit': revision, 'sourceTree': subprocess.check_output(['git', 'rev-parse', revision + '^{tree}'], cwd=ROOT, text=True).strip(),
        'ciRun': int(os.environ['GITHUB_RUN_ID']), 'ciAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
        'workflow': '.github/workflows/ci.yml', 'platform': 'linux/amd64',
        'imageId': config['Id'], 'imageConfigDigest': image_config,
        'localDescriptorDigest': config.get('Descriptor', {}).get('digest'),
        'imageTag': args.image, 'files': {p.name: sha256(p) for p in destination.iterdir()},
    })
    validate(destination, version, revision)


if __name__ == '__main__':
    main()
