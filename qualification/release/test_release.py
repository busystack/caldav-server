"""Isolated multi-version release and source/controller provenance regressions."""
import copy
from contextlib import contextmanager, ExitStack
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import release_version as versions
import release_source
import release_image


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publish = load('publish-release')
candidate = load('release-candidate')
bundle = load('release-bundle')
builder = load('build-image')
integrity = load('verify-dependency-integrity')
SHA = 'a' * 40
VERSIONS = ('0.1.0', '0.2.0', '1.0.0')
CLIENTS = ('thunderbird-system-vancouver', 'thunderbird-explicit-vancouver', 'thunderbird-explicit-berlin', 'gnome-vancouver')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), '-c', 'user.name=Release fixture',
        '-c', 'user.email=release-fixture@example.invalid', '-c', 'commit.gpgsign=false', *args], text=True).strip()


def make_jar(path, version):
    bom = json.dumps({'metadata': {'component': {'group': 'org.busystack', 'name': 'caldav-server', 'version': version}}})
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Implementation-Version: ' + version + '\n')
        archive.writestr('META-INF/maven/org.busystack/caldav-server/pom.properties', 'groupId=org.busystack\nartifactId=caldav-server\nversion=' + version + '\n')
        archive.writestr('META-INF/sbom/application.cdx.json', bom)
    return bom.encode()


def make_image_archive(path, version, revision):
    config = {'os': 'linux', 'architecture': 'amd64', 'rootfs': {'type': 'layers', 'diff_ids': []},
              'config': {'Labels': {'org.opencontainers.image.version': version,
                  'org.opencontainers.image.revision': revision,
                  'org.opencontainers.image.source': 'https://github.com/' + versions.REPOSITORY,
                  'org.opencontainers.image.licenses': 'Apache-2.0'}}}
    data = json.dumps(config).encode()
    digest = 'sha256:' + hashlib.sha256(data).hexdigest()
    path.write_bytes(bundle.tar_bytes({'config.json': data,
        'manifest.json': json.dumps([{'Config': 'config.json', 'RepoTags': None, 'Layers': []}]).encode()}))
    return digest, config


@contextmanager
def fixture(version='0.1.0'):
    with tempfile.TemporaryDirectory(prefix='caldav-release-fixture-') as temporary, ExitStack() as stack:
        directory = Path(temporary)
        root = directory / 'repo'
        root.mkdir()
        git(root, 'init', '--quiet', '--initial-branch=main')
        paths = ['pom.xml', 'Dockerfile', 'compose.yaml', 'LICENSE', 'NOTICE', 'THIRD-PARTY-NOTICES',
                 'scripts/init-db.sh', 'scripts/backup.sh', 'scripts/restore.sh', 'scripts/initialize.sh', *bundle.OPERATOR_DOCUMENTS, *bundle.SCREENSHOTS]
        for base in ('deployment', 'licenses', 'src/main/resources/META-INF/licenses'):
            paths.extend(str(p.relative_to(ROOT)) for p in (ROOT / base).rglob('*') if p.is_file())
        for name in paths:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((ROOT / name).read_bytes())
        pom = (root / 'pom.xml').read_text()
        real_version = versions.resolve(ROOT)
        (root / 'pom.xml').write_text(pom.replace('<artifactId>caldav-server</artifactId><version>' + real_version, '<artifactId>caldav-server</artifactId><version>' + version))
        (root / '.gitignore').write_text('target/\n')
        notes = root / f'docs/releases/{version}.md'
        notes.parent.mkdir(parents=True, exist_ok=True)
        notes.write_text('Qualified ' + version + ' notes. [Operations](../operations.md)\n')
        source = directory / 'candidate'
        source.mkdir()
        sbom = make_jar(source / versions.jar_name(version), version)
        (source / 'sbom.cdx.json').write_bytes(sbom)
        receipt = {'version': version, 'jarSha256': candidate.sha256(source / versions.jar_name(version)), 'status': 'passed',
                   'runtimeInputs': {name: candidate.sha256(root / name) for name in ('Dockerfile', 'compose.yaml')},
                   'clients': {name: {'status': 'passed'} for name in CLIENTS}}
        receipt_file = root / versions.receipt_path(version)
        receipt_file.parent.mkdir(parents=True, exist_ok=True)
        candidate.write_json(receipt_file, receipt)
        git(root, 'add', '.')
        git(root, 'commit', '--quiet', '-m', 'Qualified application source')
        sha = git(root, 'rev-parse', 'HEAD')
        image_config, _ = make_image_archive(source / 'container.tar.gz', version, sha)
        release_source.retain(sha, source / 'source.tar.gz', root)
        record = {'schemaVersion': 2, 'version': version, 'repository': versions.REPOSITORY,
                  'sourceCommit': sha, 'sourceTree': git(root, 'rev-parse', 'HEAD^{tree}'), 'platform': 'linux/amd64',
                  'ciRun': 123, 'ciAttempt': 2, 'workflow': '.github/workflows/ci.yml', 'imageId': image_config, 'imageConfigDigest': image_config,
                  'files': {p.name: candidate.sha256(p) for p in source.iterdir()}}
        candidate.write_json(source / 'candidate.json', record)
        for module in (candidate, bundle.candidate, publish.candidate, publish, builder):
            stack.enter_context(patch.object(module, 'ROOT', root))
        yield directory, root, source, record


class VersionAndProvenanceTest(unittest.TestCase):
    def test_older_docker_api_neutral_defaults_do_not_change_runtime_identity(self):
        defaults = {'Hostname': '', 'Domainname': '', 'Image': '', 'AttachStdin': False,
                    'AttachStdout': False, 'AttachStderr': False, 'Tty': False,
                    'OpenStdin': False, 'StdinOnce': False, 'Cmd': None, 'Volumes': None, 'OnBuild': None}
        for version in VERSIONS:
            with self.subTest(version=version), fixture(version) as (_, _, source, record):
                _, saved = release_image.saved_identity(source / 'container.tar.gz', version, record['sourceCommit'])
                actual = {'Id': record['imageId'], 'Config': {**saved['config'], **defaults}, 'RootFS': {'Layers': []}}
                with patch.object(release_image.subprocess, 'check_output', return_value='Loaded image ID: ' + record['imageId'] + '\n'), patch.object(candidate.builder, 'identity', return_value=actual):
                    self.assertEqual(candidate.load_image(source, record), actual)

    def test_configuration_comparison_retains_nondefault_and_unknown_fields(self):
        expected = {'Entrypoint': ['java'], 'User': '10001:10001', 'Env': ['A=B']}
        for key, value in [('Cmd', ['changed']), ('Volumes', {'/unexpected': {}}),
                           ('OnBuild', ['RUN unqualified-command']), ('Hostname', 'changed'),
                           ('Image', 'unexpected'), ('AttachStdin', True), ('Tty', True),
                           ('Unknown', None), ('Unknown', False), ('AttachStdin', 0),
                           ('Entrypoint', ['unqualified-command']), ('Env', ['A=changed']), ('User', '0')]:
            with self.subTest(key=key, value=value):
                self.assertNotEqual(release_image.runtime_configuration(expected),
                                    release_image.runtime_configuration({**expected, key: value}))

    def test_final_image_promotion_preserves_exact_staged_manifest_bytes_for_each_version(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                data = b'{ "schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [] }\n'
                digest = 'sha256:' + hashlib.sha256(data).hexdigest()
                def response(request, timeout):
                    result = io.BytesIO(data)
                    result.headers = {'Content-Type': 'application/vnd.oci.image.index.v1+json', 'Docker-Content-Digest': digest}
                    if request.get_method() == 'PUT':
                        self.assertEqual(request.data, data)
                        self.assertTrue(request.full_url.endswith('/manifests/' + version))
                    else:
                        self.assertTrue(request.full_url.endswith('/manifests/' + digest))
                    return result
                with patch.object(publish, 'manifest_digest', side_effect=[None, None, digest]), patch.object(publish, 'registry_headers', return_value={'Accept': 'application/vnd.oci.image.index.v1+json'}) as auth, patch.object(publish.urllib.request, 'urlopen', side_effect=response) as requests:
                    publish.promote_manifest(digest, version)
                auth.assert_called_once_with(actions='pull,push')
                self.assertEqual(requests.call_count, 2)

    def test_manifest_promotion_rejects_changed_bytes_and_conflicting_tags(self):
        digest = 'sha256:' + '1' * 64
        result = io.BytesIO(b'{"mediaType":"application/vnd.oci.image.manifest.v1+json"}')
        result.headers = {'Content-Type': 'application/vnd.oci.image.manifest.v1+json', 'Docker-Content-Digest': digest}
        with patch.object(publish, 'manifest_digest', return_value=None), patch.object(publish, 'registry_headers', return_value={'Accept': result.headers['Content-Type']}), patch.object(publish.urllib.request, 'urlopen', return_value=result) as requests, self.assertRaisesRegex(AssertionError, 'bytes do not match'):
            publish.promote_manifest(digest, '0.1.0')
        self.assertEqual(requests.call_count, 1)
        with patch.object(publish, 'manifest_digest', return_value='sha256:' + '2' * 64), patch.object(publish.urllib.request, 'urlopen') as requests, self.assertRaisesRegex(AssertionError, 'Conflicting'):
            publish.promote_manifest(digest, '0.1.0')
        requests.assert_not_called()
        with patch.object(publish, 'manifest_digest', return_value=digest), patch.object(publish.urllib.request, 'urlopen') as requests:
            publish.promote_manifest(digest, '0.1.0')
        requests.assert_not_called()

    def test_saved_image_version_and_revision_are_verified(self):
        for version, revision in (('0.2.0', SHA), ('0.1.0', SHA)):
            with fixture() as (_, _, source, record):
                make_image_archive(source / 'container.tar.gz', version, revision)
                with self.assertRaises(AssertionError):
                    release_image.saved_identity(source / 'container.tar.gz', record['version'], record['sourceCommit'])

    def test_daemon_local_loaded_id_can_differ_without_rebuilding_or_changing_runtime(self):
        with fixture() as (_, _, source, record):
            digest, saved = release_image.saved_identity(source / 'container.tar.gz', record['version'], record['sourceCommit'])
            loaded_id = 'sha256:' + 'b' * 64
            actual = {'Id': loaded_id, 'Os': 'linux', 'Architecture': 'amd64', 'Config': saved['config'], 'RootFS': {'Layers': []}}
            with patch.object(release_image.subprocess, 'check_output', return_value='Loaded image ID: ' + loaded_id + '\n') as load, patch.object(candidate.builder, 'identity', return_value=actual) as inspect:
                result = candidate.load_image(source, record)
            self.assertEqual(result['Id'], loaded_id)
            self.assertNotEqual(loaded_id, record['imageId'])
            self.assertEqual(record['imageConfigDigest'], digest)
            self.assertEqual(load.call_args.args[0][:3], ['docker', 'image', 'load'])
            self.assertEqual(inspect.call_args.args[0], loaded_id)
            actual['RootFS']['Layers'] = ['sha256:' + 'c' * 64]
            with patch.object(release_image.subprocess, 'check_output', return_value='Loaded image ID: ' + loaded_id + '\n'), patch.object(candidate.builder, 'identity', return_value=actual), self.assertRaisesRegex(AssertionError, 'root filesystem'):
                candidate.load_image(source, record)
            actual['RootFS']['Layers'] = []
            actual['Config'] = {**saved['config'], 'Entrypoint': ['unqualified-command']}
            with patch.object(release_image.subprocess, 'check_output', return_value='Loaded image ID: ' + loaded_id + '\n'), patch.object(candidate.builder, 'identity', return_value=actual), self.assertRaisesRegex(AssertionError, 'runtime configuration'):
                candidate.load_image(source, record)

    def test_snapshot_uses_git_blobs_without_archive_line_ending_or_export_filters(self):
        with fixture() as (_, root, _, _):
            (root / '.gitattributes').write_text('*.cmd text eol=crlf\nNOTICE export-ignore\n')
            (root / 'windows.cmd').write_bytes(b'@echo qualified\n')
            git(root, 'add', '.')
            git(root, 'commit', '--quiet', '-m', 'Qualified source with archive attributes')
            sha = git(root, 'rev-parse', 'HEAD')
            source = release_source.Snapshot(sha, root)
            self.assertEqual(source.read('windows.cmd'), b'@echo qualified\n')
            self.assertEqual(source.read('NOTICE'), (root / 'NOTICE').read_bytes())

    def test_cli_rejects_explicitly_empty_candidate_and_derives_each_version(self):
        for version in VERSIONS:
            with self.subTest(version=version), fixture(version) as (_, root, _, _):
                entry = root / 'scripts/release_version.py'
                entry.write_bytes((ROOT / 'scripts/release_version.py').read_bytes())
                result = subprocess.run([sys.executable, str(entry), '--candidate', ''], capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Expected a release candidate tag', result.stderr)
                result = subprocess.run([sys.executable, str(entry), '--candidate', version + '-rc.1'], capture_output=True, text=True, check=True)
                self.assertEqual(result.stdout.strip(), version + '-rc.1')

    def test_versions_paths_tags_and_maven_extraction(self):
        for version in VERSIONS:
            with self.subTest(version=version), fixture(version) as (_, root, source, record):
                self.assertEqual(versions.resolve(root), version)
                self.assertEqual(versions.resolve(root, source=record['sourceCommit']), version)
                self.assertEqual(versions.tag(version), 'v' + version)
                self.assertEqual(versions.jar_name(version), 'caldav-server-' + version + '.jar')
                self.assertEqual(versions.candidate_tag(version, version + '-rc.12'), version + '-rc.12')
                candidate.validate(source, version, record['sourceCommit'])
                publish.check_client_receipt(record['files'][versions.jar_name(version)], record['sourceCommit'], version)

    def test_invalid_versions_and_candidates_reject(self):
        for value in ('', '01.2.3', '1.02.3', '1.2.03', '1.2', '-1.2.3', '1.2.3-SNAPSHOT', 'v1.2.3', '1.2.3\n', '１.2.3', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                versions.stable(value)
        for version in VERSIONS:
            for tag in (None, '', 'latest', version, '9.9.9-rc.1', version + '-rc.0', version + '-rc.01', version + '-rc.1/other', version + '-rc.' + '1' * 128):
                with self.subTest(version=version, tag=tag), self.assertRaises(ValueError):
                    publish.candidate_version(version, tag)

    def test_controller_version_cannot_replace_qualified_application(self):
        with fixture() as (_, root, source, record):
            sha = record['sourceCommit']
            pom = root / 'pom.xml'
            pom.write_text(pom.read_text().replace('<artifactId>caldav-server</artifactId><version>0.1.0', '<artifactId>caldav-server</artifactId><version>0.2.0'))
            git(root, 'add', '.')
            git(root, 'commit', '--quiet', '-m', 'Newer controller version')
            controller = git(root, 'rev-parse', 'HEAD')
            self.assertEqual(versions.resolve(root), '0.2.0')
            self.assertEqual(versions.resolve(root, source=sha, requested='0.1.0'), '0.1.0')
            with self.assertRaisesRegex(ValueError, 'differs from qualified'):
                versions.resolve(root, source=sha, requested='0.2.0')
            publish.verify_source(sha, controller, 'publish')
            with self.assertRaises(AssertionError):
                publish.verify_source(sha, controller, 'stage')
            with self.assertRaises(AssertionError):
                publish.verify_source(sha, '0' * 40, 'publish')
            candidate.validate(source, '0.1.0', sha)
            with self.assertRaises(ValueError):
                candidate.validate(source, '0.2.0', sha)

    def test_every_bundle_input_is_isolated_from_newer_controller(self):
        for version in VERSIONS:
            with self.subTest(version=version), fixture(version) as (directory, root, source, record):
                digest = 'sha256:' + '1' * 64
                first = bundle.build(source, digest, directory / 'first')
                changed = ['compose.yaml', 'deployment/INSTALL.md', 'deployment/nginx-root.conf.example',
                           'deployment/nginx-subpath.conf.example', f'docs/releases/{version}.md',
                           'scripts/init-db.sh', 'scripts/backup.sh', 'scripts/restore.sh', 'LICENSE',
                           'NOTICE', 'THIRD-PARTY-NOTICES', 'docs/operations.md']
                for name in changed:
                    (root / name).write_text('Unqualified controller replacement\n')
                git(root, 'add', '.')
                git(root, 'commit', '--quiet', '-m', 'Change controller payload files')
                second = bundle.build(source, digest, directory / 'second')
                self.assertEqual(first, second)
                for path in (directory / 'first').iterdir():
                    self.assertEqual(path.read_bytes(), (directory / 'second' / path.name).read_bytes(), path.name)
                notes = publish.release_notes(record['sourceCommit'], version)
                self.assertIn('Qualified ' + version, notes)
                self.assertNotIn('Unqualified', notes)
                self.assertIn('/blob/' + record['sourceCommit'] + '/docs/operations.md', notes)
                manifest = bundle.validate_assets(directory / 'first', version, record['sourceCommit'])
                with tarfile.open(directory / 'first' / versions.deployment_name(version)) as archive:
                    prefix = 'caldav-server-' + version + '-deploy/'
                    compose = archive.extractfile(prefix + 'compose.yaml').read().decode()
                    self.assertIn(':' + version + '@' + digest, compose)
                    self.assertNotIn('build:', compose)
                    installation = archive.extractfile(prefix + 'INSTALL.md').read().decode()
                    self.assertIn(versions.jar_name(version), installation)
                    self.assertIn(versions.deployment_name(version), installation)
                    self.assertNotIn('@VERSION@', installation)
                    operations = archive.extractfile(prefix + 'docs/operations.md').read().decode()
                    self.assertIn('proxy_pass http://127.0.0.1:8080;', operations)
                    self.assertNotIn('127.' + version + ':8080', operations)
                    self.assertIn(prefix + versions.receipt_path(version), archive.getnames())
                    self.assertFalse(any('website-handoff' in n or 'docs/development/' in n for n in archive.getnames()))
                self.assertEqual(manifest['container']['localTestedImageId'], record['imageId'])

    def test_snapshot_changes_and_missing_source_inputs_reject(self):
        with fixture() as (directory, root, source, record):
            snapshot = source / 'source.tar.gz'
            data = release_source.files(snapshot.read_bytes())
            data['deployment/INSTALL.md'] = b'changed'
            snapshot.write_bytes(bundle.tar_bytes(data))
            record['files']['source.tar.gz'] = candidate.sha256(snapshot)
            candidate.write_json(source / 'candidate.json', record)
            with self.assertRaisesRegex(AssertionError, 'differs from qualified'):
                candidate.validate(source)
            qualified = release_source.Snapshot(record['sourceCommit'], root)
            with self.assertRaisesRegex(AssertionError, 'Missing qualified source input'):
                qualified.read('missing-required-deployment-file')

    def test_missing_artifacts_wrong_jar_version_and_checksums_reject(self):
        with fixture() as (_, root, source, record):
            jar = source / versions.jar_name(record['version'])
            make_jar(jar, '1.0.0')
            with self.assertRaisesRegex(AssertionError, 'checksum'):
                candidate.validate(source)
            record['files'][jar.name] = candidate.sha256(jar)
            candidate.write_json(source / 'candidate.json', record)
            with self.assertRaisesRegex(AssertionError, 'JAR implementation version'):
                candidate.validate(source)
            jar.unlink()
            with self.assertRaisesRegex(AssertionError, 'Unexpected candidate contents'):
                candidate.validate(source)

    def test_client_qualification_cannot_use_different_executable_or_runtime(self):
        with fixture() as (_, root, source, record):
            sha = record['sourceCommit']
            with self.assertRaisesRegex(AssertionError, 'another executable'):
                publish.check_client_receipt('8' * 64, sha, record['version'])
            receipt_path = root / versions.receipt_path(record['version'])
            receipt = json.loads(receipt_path.read_text())
            receipt['runtimeInputs']['Dockerfile'] = '0' * 64
            candidate.write_json(receipt_path, receipt)
            git(root, 'add', '.')
            git(root, 'commit', '--quiet', '-m', 'Invalid qualification input')
            with self.assertRaisesRegex(AssertionError, 'runtime inputs changed'):
                publish.check_client_receipt(record['files'][versions.jar_name(record['version'])], git(root, 'rev-parse', 'HEAD'), record['version'])

    def test_developer_build_resolves_identity_without_version_input(self):
        for version in VERSIONS:
            with self.subTest(version=version), fixture(version) as (_, root, source, record):
                target = root / 'target'
                target.mkdir()
                jar = target / versions.jar_name(version)
                shutil.copyfile(source / jar.name, jar)
                # Include a real license entry for the generated image input directory.
                with zipfile.ZipFile(jar, 'a') as archive:
                    archive.writestr('META-INF/licenses/example.txt', 'fixture license')
                real_run = subprocess.run
                def execute(command, *args, **kwargs):
                    return subprocess.CompletedProcess(command, 0) if command[0] == 'docker' else real_run(command, *args, **kwargs)
                with patch.object(sys, 'argv', ['build-image', '--jar', str(jar)]), patch.object(builder.subprocess, 'run', side_effect=execute) as run, patch.object(builder, 'identity', return_value={'Id': record['imageId']}):
                    builder.main()
                command = run.call_args.args[0]
                self.assertIn('APPLICATION_VERSION=' + version, command)
                self.assertIn('SOURCE_REVISION=' + record['sourceCommit'], command)
                self.assertIn('busystack-caldav:' + version, command)
                generated = root / 'target/docker-context/.image-input'
                self.assertEqual((generated / 'application.jar').read_bytes(), jar.read_bytes())
                environment = builder.compose_environment('busystack-caldav:' + version, generated.parent, version, record['sourceCommit'])
                self.assertEqual(environment['BUSYSTACK_APPLICATION_IMAGE'], 'busystack-caldav:' + version)
                self.assertEqual(environment['BUSYSTACK_BUILD_VERSION'], version)

    def test_image_metadata_and_packaged_checksum_must_agree(self):
        labels = {'org.opencontainers.image.version': '0.1.0', 'org.opencontainers.image.revision': SHA,
                  'org.opencontainers.image.source': 'https://github.com/' + versions.REPOSITORY, 'org.opencontainers.image.licenses': 'Apache-2.0'}
        config = {'Config': {'Labels': labels}, 'Os': 'linux', 'Architecture': 'amd64'}
        for key in ('org.opencontainers.image.version', 'org.opencontainers.image.revision'):
            bad = copy.deepcopy(config)
            bad['Config']['Labels'][key] = ''
            with patch.object(builder.subprocess, 'check_output', return_value=json.dumps([bad]).encode()), self.assertRaises(AssertionError):
                builder.identity('image', '0.1.0', SHA, '1' * 64)
        with patch.object(builder.subprocess, 'check_output', side_effect=[json.dumps([config]).encode(), '2' * 64 + '  application.jar']), self.assertRaisesRegex(AssertionError, 'exact expected JAR'):
            builder.identity('image', '0.1.0', SHA, '1' * 64)

    def test_staged_assets_are_consumed_exactly_and_tampering_rejects(self):
        with fixture() as (directory, root, source, record):
            staged = directory / 'stage'
            staged.mkdir()
            digest = 'sha256:' + '1' * 64
            manifest = bundle.build(source, digest, staged / 'assets')
            notes = staged / 'release-notes.md'
            notes.write_text(publish.release_notes(record['sourceCommit'], record['version']))
            installation = {'status': 'passed', 'version': record['version'], 'sourceCommit': record['sourceCommit'],
                            'jarSha256': manifest['jar']['sha256'], 'registryManifestDigest': digest,
                            'deploymentSha256': manifest['deployment']['sha256']}
            candidate.write_json(staged / 'installation.json', installation)
            stage = {'version': record['version'], 'candidateVersion': record['version'] + '-rc.1', 'sourceCommit': record['sourceCommit'], 'verificationRun': 123,
                     'verificationAttempt': 2, 'sourceSnapshotSha256': record['files']['source.tar.gz'], 'registryDigest': digest,
                     'assets': {p.name: candidate.sha256(p) for p in (staged / 'assets').iterdir()},
                     'installationSha256': candidate.sha256(staged / 'installation.json'), 'releaseNotesSha256': candidate.sha256(notes)}
            publish.validate_stage(staged, stage, record, {'id': 123, 'run_attempt': 2})
            (root / 'deployment/INSTALL.md').write_text('new controller instructions')
            git(root, 'add', '.')
            git(root, 'commit', '--quiet', '-m', 'Later controller')
            publish.validate_stage(staged, stage, record, {'id': 123, 'run_attempt': 2})
            for field, value in (('version', '0.2.0'), ('sourceCommit', SHA), ('verificationAttempt', 3)):
                with self.subTest(field=field), self.assertRaises(AssertionError):
                    publish.validate_stage(staged, stage | {field: value}, record, {'id': 123, 'run_attempt': 2})
            notes.write_text('changed staging notes')
            with self.assertRaisesRegex(AssertionError, 'notes changed'):
                publish.validate_stage(staged, stage, record, {'id': 123, 'run_attempt': 2})

    def test_publication_resumes_interrupted_upload_with_newer_controller_and_exact_staged_assets(self):
        with fixture() as (directory, root, source, record):
            staged = directory / 'stage'
            staged.mkdir()
            digest = 'sha256:' + '1' * 64
            manifest = bundle.build(source, digest, staged / 'assets')
            notes = staged / 'release-notes.md'
            notes.write_text(publish.release_notes(record['sourceCommit'], record['version']))
            candidate.write_json(staged / 'installation.json', {'status': 'passed', 'version': record['version'],
                'sourceCommit': record['sourceCommit'], 'jarSha256': manifest['jar']['sha256'],
                'deploymentSha256': manifest['deployment']['sha256'], 'registryManifestDigest': digest})
            stage = {'version': record['version'], 'candidateVersion': record['version'] + '-rc.1',
                'sourceCommit': record['sourceCommit'], 'verificationRun': 123, 'verificationAttempt': 2,
                'sourceSnapshotSha256': record['files']['source.tar.gz'], 'registryDigest': digest,
                'assets': {p.name: candidate.sha256(p) for p in (staged / 'assets').iterdir()},
                'releaseNotesSha256': candidate.sha256(notes), 'installationSha256': candidate.sha256(staged / 'installation.json')}
            candidate.write_json(staged / 'stage.json', stage)
            pom = root / 'pom.xml'
            pom.write_text(pom.read_text().replace('<artifactId>caldav-server</artifactId><version>0.1.0', '<artifactId>caldav-server</artifactId><version>0.2.0'))
            (root / 'deployment/INSTALL.md').write_text('Unqualified controller content')
            git(root, 'add', '.')
            git(root, 'commit', '--quiet', '-m', 'Later trusted controller')
            controller = git(root, 'rev-parse', 'HEAD')
            state = {'tag': False, 'image': False, 'release': None, 'interrupted': False}
            run = {'repository': {'full_name': publish.REPO}, 'head_repository': {'full_name': publish.REPO},
                'head_sha': record['sourceCommit'], 'head_branch': 'main', 'status': 'completed', 'conclusion': 'success',
                'event': 'push', 'name': 'verify', 'path': '.github/workflows/ci.yml', 'id': 123, 'run_attempt': 2}
            def api(path, missing=False, method='GET', fields=None):
                if path.endswith('/actions/runs/123'): return run
                if path.endswith('/attempts/2/jobs?per_page=100&page=1'):
                    return {'jobs': [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in publish.REQUIRED]}
                if path.endswith('/actions/runs/456'):
                    return run | {'id': 456, 'run_attempt': 1, 'event': 'workflow_dispatch', 'name': 'release', 'path': '.github/workflows/release.yml'}
                if 'packages/container/' in path: return {'visibility': 'public', 'repository': {'full_name': publish.REPO}}
                if '/git/ref/tags/' in path: return {'object': {'type': 'commit', 'sha': record['sourceCommit']}} if state['tag'] else None
                if path.endswith('/git/refs') and method == 'POST':
                    self.assertEqual(fields, {'ref': 'refs/tags/' + versions.tag(record['version']), 'sha': record['sourceCommit']})
                    state['tag'] = True
                    return {}
                if '/releases?per_page=' in path: return [state['release']] if state['release'] else []
                if path.endswith('/releases/77'): return state['release']
                self.fail('Unexpected API operation: ' + path)
            def download(run_id, name, destination):
                shutil.copytree(source if run_id == 123 else staged, destination)
            real_command = publish.command
            config = {'Id': record['imageId'], 'Os': 'linux', 'Architecture': 'amd64', 'Config': {'Labels': {
                'org.opencontainers.image.version': record['version'], 'org.opencontainers.image.revision': record['sourceCommit']}}}
            def command(*args):
                if args[:3] == ('docker', 'image', 'inspect'): return json.dumps([config])
                if args[:2] == ('docker', 'run'): return record['files'][versions.jar_name(record['version'])] + '  application.jar'
                if args[:3] == ('gh', 'release', 'verify'): return '{}'
                return real_command(*args)
            real_run = subprocess.run
            def execute(command, *args, **kwargs):
                if command[0] == 'git': return real_run(command, *args, **kwargs)
                self.assertNotIn(command[:2], (['docker', 'push'], ['docker', 'tag']))
                if command[:3] == ['gh', 'release', 'create']:
                    self.assertEqual(command[3], 'v0.1.0')
                    state['release'] = {'id': 77, 'tag_name': 'v0.1.0', 'draft': True, 'assets': [],
                        'body': notes.read_text(), 'html_url': 'https://example.invalid/release'}
                if command[:3] == ['gh', 'release', 'upload']:
                    asset = Path(command[4])
                    if len(state['release']['assets']) == 1 and not state['interrupted']:
                        state['interrupted'] = True
                        raise subprocess.CalledProcessError(1, command)
                    state['release']['assets'].append({'name': asset.name, 'digest': 'sha256:' + candidate.sha256(asset)})
                if command[:3] == ['gh', 'release', 'edit']:
                    state['release'].update(draft=False, immutable=True)
                return subprocess.CompletedProcess(command, 0)
            environment = {'GITHUB_REPOSITORY': publish.REPO, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': controller}
            def promote(staged_digest, version):
                self.assertEqual((staged_digest, version), (digest, record['version']))
                state['image'] = True
            with patch.dict('os.environ', environment), patch.object(publish, 'api', side_effect=api), patch.object(publish, 'download', side_effect=download), patch.object(publish, 'command', side_effect=command), patch.object(publish.subprocess, 'run', side_effect=execute), patch.object(publish.candidate, 'load_image', return_value=config), patch.object(publish, 'manifest_digest', side_effect=lambda tag, anonymous=False: digest if tag != record['version'] or state['image'] else None), patch.object(publish, 'promote_manifest', side_effect=promote) as promotion, patch.object(publish.bundle, 'build', side_effect=AssertionError('Publication must not regenerate assets')):
                for attempt in (1, 2):
                    arguments = ['publish', '--version', record['version'], '--verification-run', '123', '--source-sha', record['sourceCommit'],
                        '--phase', 'publish', '--stage-run', '456', '--immutability-confirmed', '--work', str(directory / ('publish-' + str(attempt)))]
                    with patch.object(sys, 'argv', arguments):
                        if attempt == 1:
                            with self.assertRaises(subprocess.CalledProcessError): publish.main()
                        else:
                            publish.main()
                promotion.assert_called_once_with(digest, record['version'])
            self.assertTrue(state['release']['immutable'])
            self.assertFalse(state['release']['draft'])
            self.assertEqual({a['name']: a['digest'] for a in state['release']['assets']}, {name: 'sha256:' + checksum for name, checksum in stage['assets'].items()})


class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        self.run = {'repository': {'full_name': publish.REPO}, 'head_repository': {'full_name': publish.REPO},
                    'path': '.github/workflows/ci.yml', 'name': 'verify', 'status': 'completed',
                    'conclusion': 'success', 'head_sha': SHA, 'head_branch': 'main', 'event': 'push'}
        self.jobs = [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in publish.REQUIRED]

    def test_correct_run_passes(self):
        publish.verification(self.run, self.jobs, SHA)

    def test_release_lookup_finds_drafts_and_handles_pagination(self):
        draft = {'id': 123, 'tag_name': 'v0.2.0', 'draft': True, 'assets': []}
        published = [{'id': index, 'tag_name': 'v9.' + str(index), 'draft': False} for index in range(100)]
        with patch.object(publish, 'api', side_effect=[published, [draft]]) as api:
            self.assertEqual(publish.release_for_tag('v0.2.0'), draft)
            self.assertEqual(api.call_count, 2)
        with patch.object(publish, 'api', return_value=[]):
            self.assertIsNone(publish.release_for_tag('v0.2.0'))
        with patch.object(publish, 'api', return_value=[draft, draft | {'id': 124}]), self.assertRaises(AssertionError):
            publish.release_for_tag('v0.2.0')

    def test_wrong_sha_failed_incomplete_and_untrusted_runs_reject(self):
        for key, value in [('head_sha', 'b' * 40), ('status', 'in_progress'), ('conclusion', 'failure'),
                           ('path', '.github/workflows/other.yml'), ('head_branch', 'fork'), ('event', 'pull_request')]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                publish.verification(self.run | {key: value}, self.jobs, SHA)
        for key in ('repository', 'head_repository'):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                publish.verification(self.run | {key: {'full_name': 'attacker/repo'}}, self.jobs, SHA)

    def test_missing_failed_or_skipped_required_job_rejects(self):
        with self.assertRaises(AssertionError):
            publish.verification(self.run, self.jobs[:-1], SHA)
        for conclusion in ('failure', 'skipped', 'cancelled', None):
            jobs = copy.deepcopy(self.jobs)
            jobs[0]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(AssertionError):
                publish.verification(self.run, jobs, SHA)

    def test_conflicting_version_rejects_matching_retry_passes(self):
        publish.matching_version(None, 'sha256:' + '1' * 64)
        publish.matching_version('sha256:' + '1' * 64, 'sha256:' + '1' * 64)
        with self.assertRaises(AssertionError):
            publish.matching_version('sha256:' + '2' * 64, 'sha256:' + '1' * 64)

    def test_conflicting_tag_rejects(self):
        with patch.object(publish, 'api', return_value={'object': {'type': 'commit', 'sha': 'b' * 40}}), self.assertRaisesRegex(AssertionError, 'Conflicting tag'):
            publish.check_release(SHA, '1.0.0')

    def test_existing_published_release_must_be_immutable(self):
        tag = {'object': {'type': 'commit', 'sha': SHA}}
        with patch.object(publish, 'api', return_value=tag), patch.object(publish, 'release_for_tag', return_value={'draft': False, 'immutable': False}), self.assertRaisesRegex(AssertionError, 'not immutable'):
            publish.check_release(SHA, '1.0.0')

    def test_registry_index_accepts_only_tested_amd64_and_attestations(self):
        digest = 'sha256:' + '4' * 64
        index = {'manifests': [{'digest': 'sha256:' + '5' * 64, 'platform': {'os': 'linux', 'architecture': 'amd64'}},
                               {'digest': 'sha256:' + '6' * 64, 'platform': {'os': 'unknown', 'architecture': 'unknown'},
                                'annotations': {'vnd.docker.reference.type': 'attestation-manifest'}}]}
        with patch.object(publish, 'command', side_effect=[json.dumps(index), json.dumps({'config': {'digest': 'sha256:' + '7' * 64}})]):
            publish.remote_image_matches({'imageId': digest, 'imageConfigDigest': 'sha256:' + '7' * 64}, digest)
        index['manifests'][0]['platform']['architecture'] = 'arm64'
        with patch.object(publish, 'command', return_value=json.dumps(index)), self.assertRaises(AssertionError):
            publish.remote_image_matches({'imageId': digest, 'imageConfigDigest': 'sha256:' + '7' * 64}, digest)

    def test_missing_remote_artifact_rejects_before_download(self):
        with patch.object(publish, 'api', return_value={'artifacts': []}), patch.object(publish.subprocess, 'run') as run:
            with self.assertRaisesRegex(AssertionError, 'Missing/ambiguous artifact'):
                publish.download(123, 'verified-candidate-123-1', Path('/unused'))
            run.assert_not_called()
    def test_active_source_has_no_removed_copies_or_patch_payload(self):
        integrity.source()

    def test_restore_corpus_remains_available_for_source_free_deployment(self):
        from types import SimpleNamespace
        import hashlib
        spec = importlib.util.spec_from_file_location('bundle_audit_corpus', ROOT / 'qualification/passkeys/audit_corpus.py')
        corpus_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(corpus_module)
        with tempfile.TemporaryDirectory(prefix='extracted deployment ') as temporary:
            deployment = Path(temporary)
            self.assertFalse((deployment / 'src').exists())
            corpus = corpus_module.Corpus(SimpleNamespace(root=deployment))
            for name in corpus.VALID:
                self.assertIn(b'BEGIN:VCALENDAR', (corpus.fixture / name).read_bytes().upper())
            for zone, (original, _) in corpus.OBSERVANCE_UPLOADS.items():
                data = (corpus.fixture.parent / 'observance-until' / ('thunderbird159-' + zone + '.ics')).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), original)

    def test_packaged_modified_dependency_and_removed_classes_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lock = root / 'lock.json'
            import hashlib
            payload = io.BytesIO()
            with zipfile.ZipFile(payload, 'w') as nested:
                nested.writestr('Official.class', b'official upstream bytecode fixture')
            original = payload.getvalue()
            candidate.write_json(lock, {'artifacts': {'example.jar': {'sha256': hashlib.sha256(original).hexdigest()}}})
            jar = root / 'application.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/lib/example.jar', original)
            integrity.inspect(jar, lock)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/lib/example.jar', original + b'patched')
            with self.assertRaises(AssertionError):
                integrity.inspect(jar, lock)
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/lib/example.jar', original)
                archive.writestr('BOOT-INF/classes/renamed/Ical4jZoneRulesBuilder.class', b'stale')
            with self.assertRaises(AssertionError):
                integrity.inspect(jar, lock)


class GuideIntegrityTest(unittest.TestCase):
    def test_missing_guide_missing_screenshot_and_broken_link_fail_validation(self):
        files = {str(p.relative_to(ROOT)): p.read_bytes()
                 for base in ('docs', 'deployment') for p in (ROOT / base).rglob('*') if p.is_file()}
        bundle.validate_guide(files)
        without_guide = files.copy()
        without_guide.pop('docs/GETTING_STARTED.md')
        with self.assertRaises(KeyError):
            bundle.validate_guide(without_guide)
        without_image = files.copy()
        without_image.pop(bundle.SCREENSHOTS[0])
        with self.assertRaisesRegex(AssertionError, 'Missing guide screenshot'):
            bundle.validate_guide(without_image)
        broken = files.copy()
        broken['docs/GETTING_STARTED.md'] += b'\n[Missing supporting document](missing-guide.md)\n'
        with self.assertRaisesRegex(AssertionError, 'Broken guide reference'):
            bundle.validate_guide(broken)


if __name__ == '__main__':
    unittest.main()
