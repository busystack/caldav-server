#!/usr/bin/env python3
"""Exercise exact downloaded/staged deployment assets in arbitrary disposable directories."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


candidate = load('candidate', ROOT / 'scripts/release-candidate.py')
bundle = load('bundle', ROOT / 'scripts/release-bundle.py')
versions = candidate.versions


def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def jar_smoke(jar, extracted, directory):
    """Run the release JAR using host Java, independently of the application container."""
    project = 'busystack-jar-' + secrets.token_hex(6)
    database = directory / 'database-password'
    admin = directory / 'postgres-admin-password'
    bootstrap = directory / 'bootstrap-password'
    for path in (database, admin, bootstrap):
        path.write_text(secrets.token_urlsafe(32) + '\n')
        path.chmod(0o600 if path == bootstrap else 0o444)
    db_port, app_port = port(), port()
    image = 'postgres:18.6@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6'
    jar_secrets = directory / 'jar-secrets'
    jar_secrets.mkdir(mode=0o700)
    environment = os.environ.copy()
    environment.update(SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/busystack',
                       SPRING_DATASOURCE_USERNAME='busystack', SPRING_CONFIG_IMPORT='configtree:' + str(jar_secrets) + '/',
                       SERVER_ADDRESS='127.0.0.1', SERVER_PORT=str(app_port),
                       BUSYSTACK_PUBLIC_BASE_URL='https://calendar.example.test', BUSYSTACK_ISSUER='https://calendar.example.test')
    (jar_secrets / 'spring.datasource.password').write_bytes(database.read_bytes())
    (jar_secrets / 'spring.datasource.password').chmod(0o600)
    application = None
    with (directory / 'jar.log').open('w') as log:
        try:
            subprocess.run(['docker', 'run', '-d', '--name', project, '--publish', f'127.0.0.1:{db_port}:5432',
                            '--env', 'POSTGRES_DB=busystack', '--env', 'POSTGRES_USER=postgres',
                            '--env', 'POSTGRES_PASSWORD_FILE=/run/secrets/postgres_admin_password',
                            '--env', 'POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --data-checksums',
                            '--mount', f'type=bind,src={admin},dst=/run/secrets/postgres_admin_password,readonly',
                            '--mount', f'type=bind,src={database},dst=/run/secrets/spring.datasource.password,readonly',
                            '--mount', f'type=bind,src={extracted / "scripts/init-db.sh"},dst=/docker-entrypoint-initdb.d/10-busystack.sh,readonly', image], check=True, stdout=log, stderr=log)
            deadline = time.monotonic() + 120
            while subprocess.run(['docker', 'exec', project, 'pg_isready', '-h', '127.0.0.1', '-U', 'busystack', '-d', 'busystack'], stdout=log, stderr=log).returncode:
                assert time.monotonic() < deadline, 'JAR database did not become ready'
                time.sleep(0.5)
            subprocess.run(['java', '-jar', str(jar), '--spring.main.web-application-type=none', '--busystack.bootstrap=true',
                            '--busystack.bootstrap-username=jaradmin', '--busystack.bootstrap-password-file=' + str(bootstrap)], env=environment, check=True, stdout=log, stderr=log, timeout=180)
            for _ in range(2):
                application = subprocess.Popen(['java', '-jar', str(jar)], env=environment, stdout=log, stderr=log)
                deadline = time.monotonic() + 180
                while True:
                    assert application.poll() is None, 'Downloaded JAR exited during startup'
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{app_port}/actuator/health/readiness', timeout=2) as response:
                            if response.status == 200 and json.load(response)['status'] == 'UP':
                                break
                    except (OSError, ValueError):
                        assert time.monotonic() < deadline, 'JAR startup deadline exceeded'
                        time.sleep(0.5)
                for endpoint, expected in (('/login', 200), ('/dav/', 401), ('/.well-known/oauth-authorization-server', 200)):
                    try:
                        response = urllib.request.urlopen(f'http://127.0.0.1:{app_port}' + endpoint, timeout=10)
                    except urllib.error.HTTPError as error:
                        response = error
                    with response:
                        assert response.status == expected
                        if endpoint == '/dav/':
                            assert 'Basic' in response.headers.get('WWW-Authenticate', '')
                        elif endpoint.endswith('oauth-authorization-server'):
                            assert json.load(response)['issuer'] == 'https://calendar.example.test'
                account = subprocess.check_output(['docker', 'exec', project, 'psql', '-U', 'postgres', '-d', 'busystack', '-Atc', "SELECT count(*) FROM accounts WHERE username='jaradmin' AND enabled;"])
                assert account.strip() == b'1'
                application.terminate()
                application.wait(timeout=30)
                application = None
        finally:
            if application and application.poll() is None:
                application.terminate()
                application.wait(timeout=30)
            subprocess.run(['docker', 'rm', '--force', '--volumes', project], stdout=log, stderr=log, check=True)
    return {'status': 'passed', 'hostJava': subprocess.check_output(['java', '--version'], text=True).splitlines()[0], 'bootstrapAndRestartPersistence': True, 'loginDavChallengeAndIssuer': True}


def https_bundle_smoke(extracted, image, browser_image='busystack-passkey-browser:qualification'):
    """Use the extracted deployment and proxy example with controller-owned test inputs."""
    sys.path.insert(0, str(ROOT / 'qualification/passkeys'))
    browser = load('bundle_browser', ROOT / 'qualification/passkeys/run.py')
    browser.ROOT = extracted
    original_override = browser.proxy.override
    def extracted_proxy(root, fixture, application_image, base, proxy_port):
        from urllib.parse import urlsplit
        public = urlsplit(base)
        template = (extracted / 'nginx-subpath.conf.example').read_text()
        template = template.replace('listen 443 ssl;', 'listen 8443 ssl;')
        template = template.replace('server_name calendar.example.org;', 'server_name ' + public.hostname + ';')
        template = template.replace('calendar.example.org', public.netloc).replace('/calendar', public.path)
        template = template.replace('/etc/ssl/caldav/fullchain.pem', '/tls/server.pem').replace('/etc/ssl/caldav/privkey.pem', '/tls/server.key')
        template = template.replace('http://127.0.0.1:8080', 'http://application:8080')
        template = ('worker_processes 1;\nerror_log /dev/stderr warn;\npid /tmp/nginx.pid;\nevents { worker_connections 128; }\nhttp {\n'
                    'access_log off;\nclient_body_temp_path /tmp/client_temp;\nproxy_temp_path /tmp/proxy_temp;\n'
                    'fastcgi_temp_path /tmp/fastcgi_temp;\nuwsgi_temp_path /tmp/uwsgi_temp;\nscgi_temp_path /tmp/scgi_temp;\n' + template + '}\n')
        return original_override(root, fixture, application_image, base, proxy_port, template=template)
    browser.proxy.override = extracted_proxy
    browser.operations.ROOT = extracted
    previous_argv = sys.argv
    sys.argv = ['bundle-browser', '--application-image', image, '--browser-image', browser_image, '--proxy', '--groups', '--restore']
    try:
        browser.main()
    finally:
        sys.argv = previous_argv
        browser.proxy.override = original_override


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets')
    parser.add_argument('--public', action='store_true')
    parser.add_argument('--version', help='Required for public verification; must match the asset manifest')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    result = {'status': 'failed', 'publicAnonymous': args.public}
    try:
        with tempfile.TemporaryDirectory(prefix='busystack install ') as temporary:
            directory = Path(temporary)
            assets = Path(args.assets).resolve() if args.assets else directory / 'download'
            environment = os.environ.copy()
            if args.public:
                assert not args.assets and args.version, '--public requires an explicit --version'
                versions.stable(args.version)
                assets.mkdir()
                # urllib sends neither GitHub auth headers nor cookies.
                with urllib.request.urlopen('https://api.github.com/repos/busystack/caldav-server/releases/tags/' + versions.tag(args.version), timeout=60) as response:
                    release = json.load(response)
                assert release['immutable'] and not release['draft']
                for asset in release['assets']:
                    assert Path(asset['name']).name == asset['name']
                    with urllib.request.urlopen(asset['browser_download_url'], timeout=180) as response, (assets / asset['name']).open('wb') as output:
                        shutil.copyfileobj(response, output)
                # Empty Docker config and removal of auth environment make pull anonymous.
                docker_config = directory / 'anonymous-docker'
                docker_config.mkdir()
                (docker_config / 'config.json').write_text('{}\n')
                environment['DOCKER_CONFIG'] = str(docker_config)
                for key in ('GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'DOCKER_AUTH_CONFIG', 'REGISTRY_AUTH_FILE'):
                    environment.pop(key, None)
            manifest = bundle.validate_assets(assets, args.version)
            if args.public:
                assert {a['name']: a['digest'] for a in release['assets']} == {p.name: 'sha256:' + candidate.sha256(p) for p in assets.iterdir()}
            image = manifest['container']['image']
            assert image.endswith('@' + manifest['container']['registryManifestDigest'])
            subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', image], env=environment, check=True)
            config = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image], env=environment))[0]
            assert config['Config']['Labels']['org.opencontainers.image.revision'] == manifest['sourceCommit']
            assert config['Config']['Labels']['org.opencontainers.image.version'] == manifest['version']
            jar_sha = subprocess.check_output(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', image, '/opt/busystack/application.jar'], env=environment, text=True).split()[0]
            assert jar_sha == manifest['jar']['sha256']
            assert config['Architecture'] == 'amd64' and config['Os'] == 'linux'
            with tarfile.open(assets / manifest['deployment']['name']) as archive:
                archive.extractall(directory, filter='data')
            extracted = directory / ('caldav-server-' + manifest['version'] + '-deploy')
            assert not (extracted / 'src').exists() and not (extracted / 'pom.xml').exists()
            assert 'build:' not in (extracted / 'compose.yaml').read_text()
            assert image in (extracted / 'compose.yaml').read_text()
            # Reuse established procedure assertions while resolving every deployment path
            # from the downloaded archive. Containers mount only extracted/generated files.
            operations = load('bundle_operations', ROOT / 'scripts/verify-operations.py')
            operations.ROOT = extracted
            previous_environment = os.environ.copy()
            os.environ.clear()
            os.environ.update(environment)
            try:
                operations.verify(argparse.Namespace(image=image), None)
                operations.verify(argparse.Namespace(image=image), '9')
                https_bundle_smoke(extracted, image)
                result['jarAlternative'] = jar_smoke(assets / manifest['jar']['name'], extracted, directory)
            finally:
                os.environ.clear()
                os.environ.update(previous_environment)
            result.update(status='passed', version=manifest['version'], sourceCommit=manifest['sourceCommit'],
                          jarSha256=manifest['jar']['sha256'], registryManifestDigest=manifest['container']['registryManifestDigest'],
                          deploymentSha256=manifest['deployment']['sha256'],
                          sourceDirectoryMounts=False, extractionDirectoryContainsSpaces=True, profiles=['same-version', 'V9-upgrade', 'HTTPS-proxy-browser-local-restore'])
    finally:
        candidate.write_json(args.output, result)


if __name__ == '__main__':
    main()
