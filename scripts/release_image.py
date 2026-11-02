"""Keep saved runtime configuration identity separate from daemon-local image IDs."""
import hashlib
import json
from pathlib import PurePosixPath
import re
import subprocess
import tarfile


def saved_identity(path, version, revision):
    with tarfile.open(path, 'r:*') as archive:
        manifest = json.load(archive.extractfile('manifest.json'))
        assert len(manifest) == 1, 'Expected one saved Linux AMD64 image'
        name = manifest[0]['Config']
        assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts, 'Unsafe image configuration path'
        member = archive.getmember(name)
        assert member.isfile(), 'Expected a regular image configuration blob'
        data = archive.extractfile(member).read()
    config = json.loads(data)
    assert config['os'] == 'linux' and config['architecture'] == 'amd64', 'Unqualified saved image platform'
    labels = config['config']['Labels']
    assert labels['org.opencontainers.image.version'] == version, 'Saved image version differs from Maven'
    assert labels['org.opencontainers.image.revision'] == revision, 'Saved image revision differs from qualified source'
    assert labels['org.opencontainers.image.source'] == 'https://github.com/busystack/caldav-server'
    assert labels['org.opencontainers.image.licenses'] == 'Apache-2.0'
    assert config['rootfs']['type'] == 'layers'
    return 'sha256:' + hashlib.sha256(data).hexdigest(), config


def load(path, version, revision, jar_sha, inspect):
    digest, expected = saved_identity(path, version, revision)
    output = subprocess.check_output(['docker', 'image', 'load', '--input', str(path)], text=True)
    references = []
    for line in output.splitlines():
        for prefix in ('Loaded image ID: ', 'Loaded image: '):
            if line.startswith(prefix):
                references.append(line.removeprefix(prefix))
    assert len(references) == 1, 'Missing or ambiguous loaded image identity'
    actual = inspect(references[0], version, revision, jar_sha)
    assert re.fullmatch(r'sha256:[0-9a-f]{64}', actual['Id']), 'Invalid loaded image ID'
    assert actual['RootFS']['Layers'] == expected['rootfs']['diff_ids'], 'Loaded image root filesystem differs from verified archive'
    assert actual['Config'] == expected['config'], 'Loaded image runtime configuration differs from verified archive'
    return actual, digest
