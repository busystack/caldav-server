#!/usr/bin/env python3
"""Generate digest-pinned, reproducible deployment assets from a verified candidate."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import os
import posixpath
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
    'docs/GETTING_STARTED.md',
    'docs/portal.md',
    'docs/administration.md',
    'docs/portability.md',
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
    'docs/adr/0001-protocol-engine.md',
    'docs/adr/0002-native-oauth-refresh.md',
    'docs/conformance.md',
    'docs/ical4j-parser-patch.md',
    'docs/ical4j-timezone-patch.md',
    'docs/security-verification.md',
    'docs/standards-corrections.md',
    'docs/standards-review.md',
    'investigation/oauth/README.md',
    'investigation/protocol/src/test/java/org/busystack/caldav/investigation/BedeworkHttpTest.java',
    'qualification/clients/README.md',
    'qualification/evidence/README.md',
    'qualification/evidence/vjournal-admission.md',
    'qualification/evidence/vjournal-nextcloud-35.0.3.jsonl',
    'qualification/evidence/releases/0.0.1/summary.json',
    'qualification/evidence/releases/0.1.0/README.md',
    'qualification/evidence/releases/0.1.0/verification.json',
    'qualification/keycloak/Dockerfile',
    'qualification/passkeys/README.md',
    'qualification/portability/README.md',
    'qualification/security/README.md',
    'pom.xml',
    '.github/workflows/ci.yml',
)
SCREENSHOTS = tuple(f'docs/images/getting-started/{name}.png' for name in (
    '01-protected-setup',
    '02-sign-in',
    '03-create-passkey',
    '04-passkey-saved',
    '05-overview',
    '06-server-administration',
    '07-accounts',
    '08-create-account',
    '09-account-handoff',
    '10-resource-type',
    '11-empty-task-list',
    '12-resources',
    '13-sharing',
    '14-security',
    '15-recovery-codes',
    '16-connect-an-app',
    '17-client-configuration',
    '18-create-application-password',
    '19-password-created',
    '20-import-destination',
    '21-import-preview',
    '22-import-result',
    '23-audit',
    '24-diagnostics',
    '25-shared-overview',
    '26-returning-verification',
    '27-sharing-error',
    '28-share-removal-review',
    '29-credential-management',
    '30-import-report-unavailable',
))


def heading_anchors(document):
    """Return GitHub-style ATX heading identifiers, with duplicate suffixes."""
    identifiers = set()
    occurrences = {}
    fenced = False
    for line in document.splitlines():
        if re.match(r'^\s*(?:```|~~~)', line):
            fenced = not fenced
        if fenced:
            continue
        heading = re.match(r'^ {0,3}#{1,6}\s+(.+?)\s*#*$', line)
        if heading is None:
            continue
        title = re.sub(r'\[([^]]+)\]\([^)]+\)', r'\1', heading[1])
        identifier = re.sub(r'[^\w -]', '', title.lower()).replace(' ', '-')
        occurrence = occurrences.get(identifier, 0)
        occurrences[identifier] = occurrence + 1
        identifiers.add(identifier if occurrence == 0 else f'{identifier}-{occurrence}')
    identifiers.update(re.findall(r'<a\s+(?:id|name)="([^"]+)"', document))
    return identifiers


def validate_links(name, files, label):
    from urllib.parse import unquote
    for link in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', files[name].decode()):
        if link.startswith(('https://', 'http://', 'mailto:')):
            continue
        path, separator, anchor = link.partition('#')
        target = posixpath.normpath(posixpath.join(posixpath.dirname(name), unquote(path))) if path else name
        assert target in files, f'Broken {label} reference: {name}: {link}'
        if separator and anchor and target.endswith('.md'):
            assert unquote(anchor) in heading_anchors(files[target].decode()), (
                f'Broken {label} heading anchor: {name}: {link}')


def validate_guide(files):
    """Check that navigation, headings and final screenshots are present."""
    guide = 'docs/GETTING_STARTED.md'
    text = files[guide].decode()
    for name in SCREENSHOTS:
        assert name in files, 'Missing guide screenshot: ' + name
        assert files[name].startswith(b'\x89PNG\r\n\x1a\n'), 'Invalid guide PNG: ' + name
        assert name.removeprefix('docs/') in text, 'Unreferenced guide screenshot: ' + name
    validate_links(guide, files, 'guide')


def validate_document_links(files):
    """Supporting documents must remain usable in a source-free installation."""
    for name in files:
        if name.endswith('.md'):
            validate_links(name, files, 'deployment document')


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
    files, used, notices = deployment_files(qualified, version, compose, identity, Path(source) / 'sbom.cdx.json')
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
        'container': {'image': image, 'registryManifestDigest': digest, 'localTestedImageId': record['imageId'],
                      'imageConfigDigest': record['imageConfigDigest'],
                      'testedArchiveSha256': record['files']['container.tar.gz'], 'platform': 'linux/amd64'},
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


def deployment_files(qualified, version, compose, identity, sbom):
    """Shared production payload selection; identity is supplied by the caller."""
    files = {'compose.yaml': compose.encode(), 'SOURCE.json': (json.dumps(identity, indent=2, sort_keys=True) + '\n').encode()}
    used = {'compose.yaml'}
    def content(name, render=False):
        used.add(name)
        data = qualified.read(name)
        return data.replace(b'@VERSION@', version.encode()) if render else data
    for name in qualified.under('deployment'):
        files[name.removeprefix('deployment/')] = content(name, render=True)
    for name in ('init-db.sh', 'backup.sh', 'restore.sh', 'initialize.sh'):
        files['scripts/' + name] = content('scripts/' + name)
    for name in (*OPERATOR_DOCUMENTS, f'docs/releases/{version}.md'):
        files[name] = content(name, render=True)
    for name in SCREENSHOTS:
        files[name] = content(name)
    for name in ('nginx-root.conf.example', 'nginx-subpath.conf.example'):
        files['deployment/' + name] = content('deployment/' + name, render=True)
    validate_guide(files)
    receipt = versions.receipt_path(version)
    files[receipt] = content(receipt)
    notices = {name: content(name) for name in ('LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES')}
    for base in ('licenses', 'src/main/resources/META-INF/licenses'):
        for name in qualified.under(base):
            destination = 'licenses/' + name.removeprefix(base + '/')
            data = content(name)
            assert destination not in notices or notices[destination] == data, 'Conflicting license input'
            notices[destination] = data
    notices['runtime-dependency-inventory.json'] = Path(sbom).read_bytes()
    files.update(notices)
    validate_document_links(files)
    return files, used, notices


def validate_container_identity(manifest, selected, *, allow_local_unpublished=False):
    container = manifest['container']
    digest = container['registryManifestDigest']
    if digest is None and allow_local_unpublished:
        # Only the explicitly unpublished qualification helper may bind an archive
        # to a real immutable local image ID. This is never a registry identity.
        assert manifest.get('qualificationKind') == 'local-unpublished'
        assert manifest['verification'] == {'status':'local-unpublished','run':None,'attempt':None,'url':None}
        assert container.get('published') is False
        image_id = container['localTestedImageId']
        assert isinstance(image_id, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', image_id)
        assert container['image'] == image_id
    else:
        assert isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest)
        assert container['image'] == f'ghcr.io/{candidate.REPOSITORY}:{selected}@{digest}'


def validate_assets(directory, version=None, source_sha=None, *, allow_local_unpublished=False):
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
    validate_container_identity(manifest, selected, allow_local_unpublished=allow_local_unpublished)
    with tarfile.open(directory / versions.deployment_name(selected)) as archive:
        prefix = 'caldav-server-' + selected + '-deploy/'
        members = archive.getmembers()
        assert all(p.isfile() and p.name.startswith(prefix) and '..' not in Path(p.name).parts for p in members)
        contents = {p.name.removeprefix(prefix): archive.extractfile(p).read() for p in members}
        assert len(contents) == len(members), 'Duplicate deployment entries'
    assert {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()} == manifest['deploymentFiles'], 'Deployment content mismatch'
    validate_guide(contents)
    validate_document_links(contents)
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
