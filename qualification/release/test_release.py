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

    def fixture(self, directory):
        jar = directory / 'caldav-server-0.0.1.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Implementation-Version: 0.0.1\n')
            archive.writestr('META-INF/sbom/application.cdx.json', '{}')
        (directory / 'sbom.cdx.json').write_text('{}')
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
            for name in ('SHA256SUMS', 'release-manifest.json', 'notices.tar.gz'):
                self.assertEqual((root / 'first' / name).read_bytes(), (root / 'second' / name).read_bytes())
            with self.assertRaises(FileExistsError):
                bundle.build(source, digest, root / 'first')

    def test_active_source_has_no_removed_copies_or_patch_payload(self):
        integrity.source()

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
