#!/usr/bin/env python3
"""Check every packaged runtime JAR against independently retrieved Maven Central bytes."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_version as versions

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'qualification/distribution/runtime-artifacts.json'
REMOVED = ('Ical4jCalendarParser', 'Ical4jZoneRulesBuilder')


def inspect(path, lock=LOCK):
    record = json.loads(Path(lock).read_text())
    expected = record['artifacts']
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        assert len(names) == len(set(names)), 'Duplicate executable archive entries'
        assert not any(entry.startswith('BOOT-INF/classes/') and entry.endswith('.class') and not entry.startswith('BOOT-INF/classes/org/busystack/') for entry in names), 'Unexpected application class namespace'
        assert not any(any(name in entry for name in REMOVED) for entry in names), 'Removed implementation in JAR'
        assert not any(entry.startswith(('BOOT-INF/classes/net/fortuna/', 'BOOT-INF/classes/org/springframework/')) for entry in names), 'Shadowed third-party classes'
        loader = {entry: hashlib.sha256(jar.read(entry)).hexdigest() for entry in names if not entry.startswith('BOOT-INF/') and entry.endswith('.class')}
        assert loader == record.get('executableLoader', {}).get('classes', {}), 'Executable loader differs from official artifact'
        actual = {entry.removeprefix('BOOT-INF/lib/'): hashlib.sha256(jar.read(entry)).hexdigest()
                  for entry in names if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar')}
        assert actual == {name: item['sha256'] for name, item in expected.items()}, 'Packaged dependency differs from official artifact lock'
        for entry in names:
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(jar.read(entry))) as dependency:
                    assert not any(any(name in member for name in REMOVED) for member in dependency.namelist()), 'Removed implementation hidden in dependency'
    return {'status': 'passed', 'officialArtifacts': len(actual), 'officialLoaderClasses': len(loader), 'jarSha256': hashlib.sha256(Path(path).read_bytes()).hexdigest()}


def source():
    for directory in ('src', 'scripts', '.github'):
        assert not any(path.name.endswith('.patch') for path in (ROOT / directory).rglob('*')), 'Patch payload in active tree'
    for path in (ROOT / 'src/main').rglob('*'):
        assert not any(name in path.name for name in REMOVED), 'Removed implementation reintroduced'
        if path.suffix == '.java':
            text = path.read_text()
            assert not any(name in text for name in REMOVED), 'Removed implementation referenced'
            assert 'package net.fortuna.' not in text, 'Shadowed upstream namespace'
            assert 'class ParameterParser' not in text and 'class ComponentListParser' not in text, 'Copied parser internals'
    assert not list((ROOT / 'docs').glob('ical4j-*.patch')), 'Obsolete patch payload reintroduced'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', default=str(ROOT / 'target' / versions.jar_name(versions.resolve(ROOT))))
    args = parser.parse_args()
    source()
    print(json.dumps(inspect(args.jar), sort_keys=True))
