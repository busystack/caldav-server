#!/usr/bin/env python3
"""Exercise packaged private staging on Compose tmpfs and a standalone JAR restart.

Only disposable fixture files/projects are touched. Seeded files model interrupted
staging; actual HTTP preview/confirmation behavior has separate regression tests.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('operations', ROOT / 'scripts/verify-operations.py')
operations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operations)


def ready(base, process):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Standalone JAR stopped before readiness; inspect private fixture log')
        try:
            with urllib.request.urlopen(base + '/actuator/health/readiness', timeout=2) as response:
                if response.status == 200 and json.load(response)['status'] == 'UP':
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(.25)
    raise RuntimeError('Standalone JAR readiness deadline exceeded')


def memory_failure_probe(java_home, directory):
    """Actual bounded HotSpot allocation failure, rather than a manually thrown Error."""
    source = directory / 'HeapExhaustionProbe.java'
    source.write_text('public final class HeapExhaustionProbe { public static void main(String[] args) { var retained = new java.util.ArrayList<byte[]>(); for (;;) retained.add(new byte[1024 * 1024]); } }\n')
    subprocess.run([str(java_home.resolve() / 'bin/javac'), '-d', str(directory), str(source)], check=True, capture_output=True, timeout=30)
    result = subprocess.run([str(java_home.resolve() / 'bin/java'), '-Xmx16m', '-XX:+ExitOnOutOfMemoryError', '-cp', str(directory), 'HeapExhaustionProbe'], capture_output=True, text=True, timeout=30)
    assert result.returncode == 3, 'The selected JVM must terminate on actual allocation failure'
    return {'exitCode': result.returncode, 'heapLimitBytes': 16 * 1024 * 1024, 'policy': '-XX:+ExitOnOutOfMemoryError', 'actualAllocationFailure': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-image', required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/qualification/portability/temporary-storage.json')
    args = parser.parse_args()
    env = os.environ.copy()
    env.update(operations.development_environment(args.application_image))
    image_id = operations.execute(['docker', 'image', 'inspect', '--format', '{{.Id}}', args.application_image], env)
    with tempfile.TemporaryDirectory(prefix='busystack-storage-') as temporary:
        root = Path(temporary)
        root.chmod(0o700)
        config = root / 'secrets'
        config.mkdir(mode=0o700)
        for name in ('spring.datasource.password', 'admin-password'):
            path = config / name
            path.write_text(secrets.token_urlsafe(32) + '\n')
            path.chmod(0o444)
        override = root / 'compose.yaml'
        override.write_text("services:\n  database:\n    ports:\n      - '127.0.0.1::5432'\n  application:\n    ports: !override\n      - '127.0.0.1::8080'\n")
        env.update(COMPOSE_FILE=f'{ROOT / "compose.yaml"}:{override}', COMPOSE_PATH_SEPARATOR=':',
                   COMPOSE_PROJECT_NAME='busystack-storage-' + secrets.token_hex(6),
                   BUSYSTACK_DATABASE_PASSWORD_FILE=str(config / 'spring.datasource.password'),
                   BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=str(config / 'admin-password'),
                   BUSYSTACK_PUBLIC_BASE_URL='https://calendar.example.test', BUSYSTACK_ISSUER='https://calendar.example.test')
        process = None
        try:
            operations.compose(env, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240')
            application = operations.compose(env, 'ps', '--quiet', 'application')
            state = json.loads(operations.execute(['docker', 'inspect', application], env))[0]
            assert state['HostConfig']['ReadonlyRootfs']
            assert '-XX:+ExitOnOutOfMemoryError' in state['Config']['Entrypoint']
            assert '/tmp' in state['HostConfig']['Tmpfs']
            blocks, block_size = map(int, operations.compose(env, 'exec', '-T', 'application', 'stat', '-f', '-c', '%b %S', '/tmp').split())
            assert blocks * block_size == 384 * 1024 * 1024
            operations.compose(env, 'exec', '-T', 'application', 'sh', '-ec',
                               'test "$(stat -c %a /tmp/busystack-portability)" = 700; test "$(stat -c %a /tmp/busystack-portability/multipart)" = 700; umask 077; printf fixture > /tmp/busystack-portability/data-11111111-1111-4111-8111-111111111111.private; test "$(stat -c %a /tmp/busystack-portability/data-11111111-1111-4111-8111-111111111111.private)" = 600')
            operations.compose(env, 'kill', '--signal', 'SIGKILL', 'application')
            operations.compose(env, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240', 'application')
            operations.compose(env, 'exec', '-T', 'application', 'sh', '-ec',
                               'test ! -e /tmp/busystack-portability/data-11111111-1111-4111-8111-111111111111.private')
            operations.compose(env, 'stop', 'application')
            database = operations.compose(env, 'port', 'database', '5432').splitlines()[0]
            temp = root / 'jar-temp'
            temp.mkdir(mode=0o700)
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            jar_env = os.environ.copy()
            jar_env.update(SPRING_CONFIG_IMPORT='configtree:' + str(config) + '/',
                           SPRING_DATASOURCE_URL='jdbc:postgresql://' + database + '/busystack',
                           SPRING_DATASOURCE_USERNAME='busystack', SERVER_PORT=str(port), SERVER_ADDRESS='127.0.0.1',
                           BUSYSTACK_PUBLIC_BASE_URL=f'https://localhost:{port}', BUSYSTACK_ISSUER=f'https://localhost:{port}')
            command = [str(args.java_home.resolve() / 'bin/java'), '-Xmx256m', '-XX:+ExitOnOutOfMemoryError', '-Djava.io.tmpdir=' + str(temp), '-jar', str(args.jar.resolve())]
            with (root / 'standalone.log').open('wb') as log:
                process = subprocess.Popen(command, env=jar_env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                ready(f'http://127.0.0.1:{port}', process)
                staging = temp / 'busystack-portability'
                assert staging.stat().st_mode & 0o777 == 0o700
                orphan = staging / ('data-' + str(uuid.uuid4()) + '.private')
                orphan.write_text('BEGIN:VCALENDAR\r\nPRIVATE CRASH FIXTURE\r\nEND:VCALENDAR\r\n')
                orphan.chmod(0o600)
                multipart = staging / 'multipart/upload_fixture_0001.tmp'
                multipart.write_text('private interrupted upload')
                multipart.chmod(0o600)
                unrelated = staging / 'operator-notes'
                unrelated.write_text('preserve unrelated file')
                unrelated.chmod(0o600)
                process.kill()
                assert process.wait(timeout=30) == -9
                assert orphan.exists() and multipart.exists(), 'Standalone remnants remain until application startup'
                probe_directory = root / 'memory-probe'
                probe_directory.mkdir(mode=0o700)
                memory_probe = memory_failure_probe(args.java_home, probe_directory)
                assert orphan.exists() and multipart.exists()
                process = subprocess.Popen(command, env=jar_env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                ready(f'http://127.0.0.1:{port}', process)
                assert not orphan.exists() and not multipart.exists()
                assert unrelated.read_text() == 'preserve unrelated file'
                process.terminate()
                assert process.wait(timeout=45) == 143
            result = dict(imageId=image_id, jarSha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
                          composeReadOnly=True, composeTmpfsBytes=384 * 1024 * 1024,
                          composeForcedTerminationClean=True, standaloneForcedTerminationRemnantsObserved=True,
                          standaloneStartupOrphansRemoved=True, unrelatedFilePreserved=True,
                          directoryMode='0700', seededFileMode='0600', gracefulShutdown=True, memoryFailureProbe=memory_probe,
                          scope='Real packaged startup/shutdown; seeded crash remnants. HTTP imports and transaction interruption are separate tests.')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(result))
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.wait(timeout=30)
            operations.compose(env, 'down', '--volumes', '--remove-orphans', '--timeout', '10')


if __name__ == '__main__':
    main()
