import copy
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publish = load('publish-release')
candidate = load('release-candidate')
bundle = load('release-bundle')
integrity = load('verify-dependency-integrity')
SHA = 'a' * 40


class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        self.run = {'repository': {'full_name': publish.REPO}, 'head_repository': {'full_name': publish.REPO},
                    'path': '.github/workflows/ci.yml', 'name': 'verify', 'status': 'completed',
                    'conclusion': 'success', 'head_sha': SHA, 'head_branch': 'main', 'event': 'push'}
        self.jobs = [{'name': name, 'status': 'completed', 'conclusion': 'success'} for name in publish.REQUIRED]

    def test_correct_run_passes(self):
        publish.verification(self.run, self.jobs, SHA)

    def test_release_lookup_finds_drafts_and_handles_pagination(self):
        from unittest.mock import patch
        draft = {'id': 123, 'tag_name': 'v0.0.1', 'draft': True, 'assets': []}
        published = [{'id': index, 'tag_name': 'v9.' + str(index), 'draft': False} for index in range(100)]
        with patch.object(publish, 'api', side_effect=[published, [draft]]) as api:
            self.assertEqual(publish.release_for_tag(), draft)
            self.assertEqual(api.call_count, 2)
        with patch.object(publish, 'api', return_value=[]):
            self.assertIsNone(publish.release_for_tag())
        with patch.object(publish, 'api', return_value=[draft, draft | {'id': 124}]), self.assertRaises(AssertionError):
            publish.release_for_tag()

    def test_controller_can_resume_frozen_source_without_changing_its_inputs(self):
        import hashlib
        import subprocess
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*arguments):
                return subprocess.check_output(['git', '-C', str(root), '-c', 'user.name=Release fixture',
                    '-c', 'user.email=release-fixture@example.invalid', '-c', 'commit.gpgsign=false', *arguments], text=True).strip()
            git('init', '--quiet', '--initial-branch=main')
            for name in ('Dockerfile', 'compose.yaml'):
                (root / name).write_text('qualified runtime input\n')
            notes = root / 'docs/releases/0.0.1.md'
            notes.parent.mkdir(parents=True)
            notes.write_text('Qualified release notes. [Operations](../operations.md)\n')
            jar = '7' * 64
            receipt = {'status': 'passed', 'jarSha256': jar,
                       'runtimeInputs': {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in ('Dockerfile', 'compose.yaml')},
                       'baselineReplacement': {'status': 'passed', 'correctedJarSha256': jar},
                       'clients': {name: {'status': 'passed'} for name in ('thunderbird-system-vancouver', 'thunderbird-explicit-vancouver', 'thunderbird-explicit-berlin', 'gnome-vancouver')}}
            receipt_path = root / 'qualification/evidence/releases/0.0.1/official-dependencies/verification.json'
            receipt_path.parent.mkdir(parents=True)
            candidate.write_json(receipt_path, receipt)
            git('add', '.')
            git('commit', '--quiet', '-m', 'Qualified source fixture')
            source = git('rev-parse', 'HEAD')
            (root / 'Dockerfile').write_text('later controller checkout input\n')
            notes.write_text('Later checkout notes.\n')
            git('add', '.')
            git('commit', '--quiet', '-m', 'Later controller fixture')
            controller = git('rev-parse', 'HEAD')
            with patch.object(publish, 'ROOT', root):
                publish.verify_source(source, controller, 'publish')
                publish.verify_source(controller, controller, 'stage')
                with self.assertRaises(AssertionError):
                    publish.verify_source(source, controller, 'stage')
                with self.assertRaises(AssertionError):
                    publish.verify_source('f' * 40, controller, 'publish')
                with self.assertRaises(AssertionError):
                    publish.verify_source(source, '0' * 40, 'publish')
                publish.check_client_receipt(jar, source)
                with self.assertRaises(AssertionError):
                    publish.check_client_receipt(jar)
                self.assertEqual(publish.source_bytes(source, 'Dockerfile'), b'qualified runtime input\n')
                selected_notes = publish.release_notes(source, from_git=True)
                self.assertIn('Qualified release notes.', selected_notes)
                self.assertNotIn('Later checkout notes.', selected_notes)
                self.assertIn('/blob/' + source + '/docs/operations.md', selected_notes)
            self.assertEqual(git('rev-parse', 'HEAD'), controller)

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

    def test_candidate_tags_are_versioned_readable_and_bounded_to_release(self):
        for tag in ('0.0.1-rc.1', '0.0.1-rc.12'):
            self.assertEqual(publish.candidate_version('0.0.1', tag), tag)
        for tag in (None, '', 'candidate-123-1', 'latest', '0.0.1', '0.0.2-rc.1', '0.0.1-rc.0', '0.0.1-rc.01', '0.0.1-rc.1/other', '0.0.1-rc.' + '1' * 128):
            with self.subTest(tag=tag), self.assertRaises(AssertionError):
                publish.candidate_version('0.0.1', tag)

    def test_published_notes_link_to_exact_qualified_source(self):
        notes = publish.release_notes(SHA)
        self.assertNotIn('](../', notes)
        self.assertIn('https://github.com/' + publish.REPO + '/blob/' + SHA + '/docs/operations.md', notes)
        self.assertIn('https://github.com/' + publish.REPO + '/blob/' + SHA + '/qualification/evidence/releases/0.0.1/official-dependencies/verification.json', notes)
        self.assertIn('https://github.com/' + publish.REPO + '/releases/download/v0.0.1/release-manifest.json', notes)

    def test_registry_index_accepts_only_tested_amd64_and_attestations(self):
        from unittest.mock import patch
        digest = 'sha256:' + '4' * 64
        index = {'manifests': [{'digest': 'sha256:' + '5' * 64, 'platform': {'os': 'linux', 'architecture': 'amd64'}},
                               {'digest': 'sha256:' + '6' * 64, 'platform': {'os': 'unknown', 'architecture': 'unknown'},
                                'annotations': {'vnd.docker.reference.type': 'attestation-manifest'}}]}
        with patch.object(publish, 'command', return_value=json.dumps(index)):
            publish.remote_image_matches({'imageId': digest, 'localDescriptorDigest': digest}, digest)
        index['manifests'][0]['platform']['architecture'] = 'arm64'
        with patch.object(publish, 'command', return_value=json.dumps(index)), self.assertRaises(AssertionError):
            publish.remote_image_matches({'imageId': digest}, digest)

    def test_missing_remote_artifact_rejects_before_download(self):
        from unittest.mock import patch
        with patch.object(publish, 'api', return_value={'artifacts': []}), patch.object(publish.subprocess, 'run') as run:
            with self.assertRaisesRegex(AssertionError, 'Missing/ambiguous artifact'):
                publish.download(123, 'verified-candidate-123-1', Path('/unused'))
            run.assert_not_called()

    def test_client_receipt_is_bound_to_executable_and_runtime(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('Dockerfile', 'compose.yaml'):
                (root / name).write_text('qualified runtime input')
            path = root / 'qualification/evidence/releases/0.0.1/official-dependencies/verification.json'
            path.parent.mkdir(parents=True)
            jar_sha = '7' * 64
            receipt = {'jarSha256': jar_sha, 'runtimeInputs': {name: candidate.sha256(root / name) for name in ('Dockerfile', 'compose.yaml')},
                       'status': 'passed', 'baselineReplacement': {'status': 'passed', 'correctedJarSha256': jar_sha},
                       'clients': {name: {'status': 'passed'} for name in ('thunderbird-system-vancouver', 'thunderbird-explicit-vancouver', 'thunderbird-explicit-berlin', 'gnome-vancouver')}}
            candidate.write_json(path, receipt)
            with patch.object(publish, 'ROOT', root):
                publish.check_client_receipt(jar_sha)
                with self.assertRaises(AssertionError):
                    publish.check_client_receipt('8' * 64)
                (root / 'Dockerfile').write_text('different runtime')
                with self.assertRaises(AssertionError):
                    publish.check_client_receipt(jar_sha)

    def fixture(self, directory):
        jar = directory / 'caldav-server-0.0.1.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Implementation-Version: 0.0.1\n')
            archive.writestr('META-INF/sbom/application.cdx.json', '{"metadata":{"component":{"version":"0.0.1"}}}')
            archive.writestr('META-INF/maven/org.busystack/caldav-server/pom.properties', 'version=0.0.1\n')
        (directory / 'sbom.cdx.json').write_text('{"metadata":{"component":{"version":"0.0.1"}}}')
        (directory / 'container.tar.gz').write_bytes(b'tested archive fixture')
        record = {'version': '0.0.1', 'repository': publish.REPO, 'sourceCommit': SHA, 'platform': 'linux/amd64',
                  'ciRun': 123, 'ciAttempt': 1, 'workflow': '.github/workflows/ci.yml', 'imageId': 'sha256:' + '3' * 64,
                  'files': {p.name: candidate.sha256(p) for p in directory.iterdir()}}
        candidate.write_json(directory / 'candidate.json', record)
        return record

    def test_missing_artifact_and_checksum_mismatch_reject(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.fixture(directory)
            candidate.validate(directory)
            (directory / 'container.tar.gz').write_bytes(b'changed')
            with self.assertRaises(AssertionError):
                candidate.validate(directory)
            (directory / 'container.tar.gz').unlink()
            with self.assertRaises(FileNotFoundError):
                candidate.validate(directory)

    def test_bundle_is_complete_portable_digest_pinned_and_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'candidate'
            source.mkdir()
            self.fixture(source)
            digest = 'sha256:' + '1' * 64
            first = bundle.build(source, digest, root / 'first')
            second = bundle.build(source, digest, root / 'second')
            self.assertEqual(first, second)
            with tarfile.open(root / 'first/caldav-server-0.0.1-deploy.tar.gz') as archive:
                names = {p.name for p in archive.getmembers()}
                prefix = 'caldav-server-0.0.1-deploy/'
                for name in ('compose.yaml', 'INSTALL.md', '.env.example', 'scripts/init-db.sh', 'scripts/backup.sh', 'scripts/restore.sh', 'nginx-root.conf.example', 'nginx-subpath.conf.example'):
                    self.assertIn(prefix + name, names)
                compose = archive.extractfile(prefix + 'compose.yaml').read().decode()
                self.assertIn('@' + digest, compose)
                self.assertNotIn('build:', compose)
                self.assertNotIn('/home/albert', compose)
                self.assertFalse(any('/src/' in name for name in names))
                self.assertFalse(any('/docs/development/' in name for name in names))
                self.assertNotIn(prefix + 'docs/releases/0.0.1-website-handoff.md', names)
                self.assertNotIn(prefix + 'docs/standards-corrections.md', names)
            for name in ('SHA256SUMS', 'release-manifest.json', 'notices.tar.gz'):
                self.assertEqual((root / 'first' / name).read_bytes(), (root / 'second' / name).read_bytes())
            with self.assertRaises(FileExistsError):
                bundle.build(source, digest, root / 'first')

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


if __name__ == '__main__':
    unittest.main()
