"""Read release payloads exclusively from the exact qualified Git tree."""
import gzip
import hashlib
import io
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

import release_version as versions


def archive(source, root=versions.ROOT):
    source = versions.revision(source, root)
    return subprocess.check_output(['git', '-C', str(root), 'archive', '--format=tar', source])


def files(data):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as stream:
        for entry in stream:
            if entry.isdir():
                continue
            name = PurePosixPath(entry.name)
            assert entry.isfile() and not name.is_absolute() and '..' not in name.parts, 'Unsafe source snapshot entry'
            assert entry.name not in result, 'Duplicate source snapshot entry'
            result[entry.name] = stream.extractfile(entry).read()
    return result


class Snapshot:
    def __init__(self, source, root=versions.ROOT, retained=None):
        self.source = versions.revision(source, root)
        expected = files(archive(self.source, root))
        self.files = files(Path(retained).read_bytes()) if retained else expected
        assert self.files == expected, 'Retained source snapshot differs from qualified Git tree'
        tree = subprocess.check_output(['git', '-C', str(root), 'ls-tree', '-rz', self.source])
        expected_blobs = {}
        for item in tree.split(b'\0'):
            if not item:
                continue
            metadata, name = item.split(b'\t', 1)
            mode, kind, sha = metadata.decode().split()
            assert kind == 'blob' and mode in ('100644', '100755'), 'Unsupported source tree entry'
            expected_blobs[name.decode()] = sha
        actual_blobs = {name: hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
                        for name, data in self.files.items()}
        assert actual_blobs == expected_blobs, 'Source archive does not reproduce qualified Git blobs'
        self.version = versions.from_pom(self.read('pom.xml'))

    def read(self, name):
        assert name in self.files, 'Missing qualified source input: ' + name
        return self.files[name]

    def under(self, prefix):
        names = sorted(name for name in self.files if name.startswith(prefix + '/'))
        assert names, 'Missing qualified source directory: ' + prefix
        return names


def retain(source, destination, root=versions.ROOT):
    Snapshot(source, root)
    Path(destination).write_bytes(gzip.compress(archive(source, root), mtime=0))
