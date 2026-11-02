"""Read release payloads exclusively from the exact qualified Git tree."""
import gzip
import hashlib
import io
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

import release_version as versions


def tree_entries(source, root):
    tree = subprocess.check_output(['git', '-C', str(root), 'ls-tree', '-rz', source])
    entries = {}
    for item in tree.split(b'\0'):
        if not item:
            continue
        metadata, name = item.split(b'\t', 1)
        mode, kind, sha = metadata.decode().split()
        assert kind == 'blob' and mode in ('100644', '100755'), 'Unsupported source tree entry'
        entries[name.decode()] = (mode, sha)
    return entries


def archive(source, root=versions.ROOT):
    source = versions.revision(source, root)
    entries = tree_entries(source, root)
    shas = sorted({sha for _, sha in entries.values()})
    # git archive applies eol/export attributes. Read raw objects so even a newer
    # controller's attributes cannot transform or omit qualified source bytes.
    batch = subprocess.check_output(['git', '-C', str(root), 'cat-file', '--batch'],
                                    input=('\n'.join(shas) + '\n').encode())
    blobs = {}
    cursor = 0
    for expected in shas:
        end = batch.index(b'\n', cursor)
        sha, kind, length = batch[cursor:end].decode().split()
        assert sha == expected and kind == 'blob', 'Unexpected Git object'
        cursor = end + 1
        size = int(length)
        blobs[sha] = batch[cursor:cursor + size]
        cursor += size
        assert batch[cursor:cursor + 1] == b'\n'
        cursor += 1
    assert cursor == len(batch)
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as output:
        for name, (mode, sha) in sorted(entries.items()):
            entry = tarfile.TarInfo(name)
            entry.size = len(blobs[sha])
            entry.mode = 0o755 if mode == '100755' else 0o644
            output.addfile(entry, io.BytesIO(blobs[sha]))
    return buffer.getvalue()


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
        expected_blobs = {name: sha for name, (_, sha) in tree_entries(self.source, root).items()}
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
