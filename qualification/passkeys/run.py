#!/usr/bin/env python3
"""Qualify actual browser WebAuthn/MFA over disposable localhost TLS on Linux."""

import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import secrets
import selectors
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import proxy
import tls
import oidc


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("operations", ROOT / "scripts/verify-operations.py")
operations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operations)


def ready(base, ca, public_proxy=False, check_path=None):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            path = check_path or ("/login" if public_proxy else "/actuator/health/readiness")
            with tls.opener(ca).open(base + path, timeout=2) as response:
                if response.status == 200 and (public_proxy or check_path is not None or json.load(response)["status"] == "UP"):
                    return
        except urllib.error.HTTPError as error:
            if error.code != 503 and not (public_proxy and error.code in (502, 504)):
                raise
        except urllib.error.URLError as error:
            if isinstance(error.reason, ssl.SSLCertVerificationError):
                raise
        except (TimeoutError, ConnectionError):
            pass
        # Poll a concrete readiness condition with an explicit deadline.
        threading.Event().wait(0.25)
    raise RuntimeError("Packaged TLS application did not become ready within 240 seconds")


def certificates(directory, environment):
    password = directory / "server-password"
    password.write_text(secrets.token_urlsafe(32))
    password.chmod(0o444)
    execute = lambda arguments: operations.execute(["openssl", *arguments], environment)
    execute(["req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-keyout", str(directory / "ca.key"), "-out", str(directory / "ca.pem"), "-days", "2", "-subj", "/CN=BusyStack disposable browser CA", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign"])
    execute(["req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-keyout", str(directory / "server.key"), "-out", str(directory / "server.csr"), "-subj", "/CN=localhost"])
    (directory / "extensions").write_text("subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:app.busystack.test,DNS:idp.identity.test\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature\nbasicConstraints=CA:FALSE\n")
    execute(["x509", "-req", "-in", str(directory / "server.csr"), "-CA", str(directory / "ca.pem"), "-CAkey", str(directory / "ca.key"), "-CAcreateserial", "-out", str(directory / "server.pem"), "-days", "2", "-sha256", "-extfile", str(directory / "extensions")])
    execute(["pkcs12", "-export", "-name", "server", "-inkey", str(directory / "server.key"), "-in", str(directory / "server.pem"), "-certfile", str(directory / "ca.pem"), "-out", str(directory / "server.p12"), "-passout", "file:" + str(password)])
    (directory / "server.p12").chmod(0o444)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--application-image", default="busystack-caldav:local")
    parser.add_argument("--browser-image", default="busystack-passkey-browser:qualification")
    parser.add_argument("--context-path", choices=("", "/team"), default="")
    parser.add_argument("--proxy", action="store_true", help="Use pinned Nginx HTTPS termination and the /team prefix")
    parser.add_argument("--oidc", action="store_true", help="Also qualify a real Keycloak login across distinct reserved test sites")
    args = parser.parse_args()
    if args.oidc:
        args.proxy = True
    if args.proxy:
        args.context_path = "/team"
    if os.uname().sysname != "Linux":
        parser.error("This fixture requires Linux Docker host networking; it does not silently skip")
    for image in (args.application_image, args.browser_image):
        if not image or any(character.isspace() for character in image):
            parser.error("Image references must not contain whitespace")
    environment = os.environ.copy()
    operations.execute(["docker", "info"], environment)
    args.application_image = operations.execute(["docker", "image", "inspect", "--format", "{{.Id}}", args.application_image], environment)
    args.browser_image = operations.execute(["docker", "image", "inspect", "--format", "{{.Id}}", args.browser_image], environment)
    print("BROWSER_APPLICATION_IMAGE=" + args.application_image, flush=True)
    print("BROWSER_FIXTURE_IMAGE=" + args.browser_image, flush=True)
    print("BROWSER_PROFILE=" + ("proxy-cross-site-oidc" if args.oidc else "proxy-team" if args.proxy else "embedded-tls" + args.context_path), flush=True)
    with tempfile.TemporaryDirectory(prefix="busystack-browser-") as temporary:
        directory = Path(temporary)
        directory.chmod(0o700)
        for name in ("database-password", "admin-password", "bootstrap-password"):
            path = directory / name
            path.write_text(secrets.token_urlsafe(32) + "\n")
            path.chmod(0o600 if name == "bootstrap-password" else 0o444)
        certificates(directory, environment)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        hostname = "app.busystack.test" if args.oidc else "localhost"
        base = f"https://{hostname}:{port}{args.context_path}"
        override = directory / "compose.override.yaml"
        override.write_text(
            "services:\n  application:\n"
            f"    image: {json.dumps(args.application_image)}\n"
            f"    ports: !override\n      - '127.0.0.1:{port}:8443'\n"
            "    environment:\n      SERVER_PORT: '8443'\n      SERVER_SSL_ENABLED: 'true'\n"
            "      SERVER_SSL_KEY_STORE: file:/tls/server.p12\n      SERVER_SSL_KEY_STORE_TYPE: PKCS12\n"
            f"    volumes:\n      - {json.dumps(str(directory / 'server.p12') + ':/tls/server.p12:ro')}\n"
            "    secrets:\n      - source: test_keystore_password\n        target: server.ssl.key-store-password\n"
            "    healthcheck:\n      disable: true\n"
            f"secrets:\n  test_keystore_password:\n    file: {json.dumps(str(directory / 'server-password'))}\n"
        )
        if args.proxy:
            override.write_text(proxy.override(ROOT, directory, args.application_image, base, port))
        project = "busystack-browser-" + secrets.token_hex(6)
        browser_name = project + "-chromium"
        environment.update(
            COMPOSE_FILE=f"{ROOT / 'compose.yaml'}:{override}",
            COMPOSE_PROJECT_NAME=project,
            COMPOSE_PATH_SEPARATOR=":",
            BUSYSTACK_DATABASE_PASSWORD_FILE=str(directory / "database-password"),
            BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=str(directory / "admin-password"),
            BUSYSTACK_PUBLIC_BASE_URL=base,
            BUSYSTACK_ISSUER=base,
            BUSYSTACK_HTTP_PORT="8080",
            BUSYSTACK_ALLOW_INSECURE_LOCAL="false",
        )
        issuer = None
        if args.oidc:
            operations.execute(["docker", "image", "inspect", oidc.IMAGE], environment)
            upstream_override, issuer = oidc.configure(ROOT, directory, base, operations.execute, environment)
            environment["COMPOSE_FILE"] += ":" + str(upstream_override)
        browser = None
        try:
            operations.compose(environment, "up", "-d", "--wait", "--wait-timeout", "120", "database")
            if args.oidc:
                operations.compose(environment, "up", "-d", "--no-build", "upstream")
            operations.compose(environment, "run", "--rm", "--no-deps", "--user", f"{os.getuid()}:{os.getgid()}", "--volume", f"{directory / 'bootstrap-password'}:/bootstrap-password:ro", "application", "--spring.main.web-application-type=none", "--busystack.bootstrap=true", "--busystack.bootstrap-username=browseradmin", "--busystack.bootstrap-password-file=/bootstrap-password")
            operations.compose(environment, "up", "-d", "--no-build", "application")
            if args.proxy:
                operations.compose(environment, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "reverse-proxy")
            ready(base, directory / "ca.pem", args.proxy)
            if args.oidc:
                ready(issuer, directory / "ca.pem", False, "/.well-known/openid-configuration")
                with tls.opener(directory / "ca.pem").open(issuer + "/.well-known/openid-configuration", timeout=10) as response:
                    assert json.load(response)["issuer"] == issuer
            if args.proxy:
                proxy.verify(base, directory / "ca.pem")
            try:
                tls.opener().open(base + "/actuator/health/readiness", timeout=3)
            except urllib.error.URLError as error:
                assert isinstance(error.reason, ssl.SSLCertVerificationError)
            else:
                raise AssertionError("Disposable CA was unexpectedly trusted without isolated configuration")
            browser_command = ["docker", "run", "--rm", "--name", browser_name, "--network", "host", "--interactive", "--volume", f"{directory / 'ca.pem'}:/fixture/ca.pem:ro", "--volume", f"{directory / 'bootstrap-password'}:/fixture/password:ro", "--env", "BUSYSTACK_BROWSER_CA_FILE=/fixture/ca.pem", "--env", "BUSYSTACK_BROWSER_PASSWORD_FILE=/fixture/password", "--env", "BUSYSTACK_BROWSER_USERNAME=browseradmin", "--env", "BUSYSTACK_BROWSER_URL=" + base]
            if args.oidc:
                browser_command += ["--add-host", "app.busystack.test:127.0.0.1", "--add-host", "idp.identity.test:127.0.0.1",
                    "--volume", f"{directory / 'upstream-password'}:/fixture/upstream-password:ro", "--env", "BUSYSTACK_BROWSER_OIDC=true",
                    "--env", "BUSYSTACK_UPSTREAM_USERNAME=" + oidc.USERNAME, "--env", "BUSYSTACK_FEDERATED_USERNAME=" + oidc.INTERNAL_USERNAME,
                    "--env", "BUSYSTACK_UPSTREAM_PASSWORD_FILE=/fixture/upstream-password"]
            browser_command.append(args.browser_image)
            browser = subprocess.Popen(
                browser_command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,
            )
            selector = selectors.DefaultSelector()
            selector.register(browser.stdout, selectors.EVENT_READ)
            pending = b""
            signals = set()
            deadline = time.monotonic() + 600
            password = (directory / "bootstrap-password").read_text().strip()
            while time.monotonic() < deadline:
                events = selector.select(timeout=1)
                if not events:
                    if browser.poll() is not None:
                        break
                    continue
                chunk = os.read(browser.stdout.fileno(), 8192)
                if not chunk:
                    break
                pending += chunk
                while b"\n" in pending:
                    raw, pending = pending.split(b"\n", 1)
                    line = raw.decode(errors="replace")
                    print(line.replace(password, "[redacted]"), flush=True)
                    signals.add(line)
                    if line == "READY_RESTART":
                        assert int(operations.sql(environment, "SELECT count(*) FROM user_credentials;")) == (2 if args.oidc else 1)
                        operations.compose(environment, "restart", "application")
                        ready(base, directory / "ca.pem", args.proxy)
                        browser.stdin.write(b"RESTARTED\n")
                        browser.stdin.flush()
                    elif line == "READY_DISABLE":
                        username = oidc.INTERNAL_USERNAME if args.oidc else "browseradmin"
                        operations.sql(environment, "UPDATE accounts SET enabled=false WHERE username='" + username + "';")
                        browser.stdin.write(b"DISABLED\n")
                        browser.stdin.flush()
            selector.close()
            try:
                result = browser.wait(timeout=15)
            except subprocess.TimeoutExpired as error:
                raise RuntimeError("Browser qualification exceeded its deadline") from error
            assert result == 0, "Browser qualification failed"
            for expected in ("PASSWORD_PLUS_WEBAUTHN_AND_MUTATION_PASSED", "BROWSER_DAV_CREDENTIAL_ISSUANCE_DISPLAY_ONCE_AND_REVOCATION_PASSED", "BROWSER_S256_CONSENT_TOKEN_REFRESH_AND_GRANT_REVOCATION_PASSED", "PERSISTED_PASSKEY_AFTER_RESTART_PASSED", "DISABLED_SESSION_AND_PASSKEY_PASSED"):
                assert expected in signals, expected
            if args.oidc:
                assert "CROSS_SITE_ORGANIZATION_PLUS_PASSKEY_AND_NONADMIN_PROVISIONING_PASSED" in signals
            print("PASS: trusted test-CA HTTPS, Chromium virtual CTAP2 enrollment/assertion, password+passkey MFA enforcement, session rotation, persistence across restart, disabled-session/passkey denial.")
        except Exception:
            # Emit only sanitized error diagnostics from this disposable application, before cleanup.
            raw = operations.compose(environment, "logs", "--no-log-prefix", "--tail", "120", "application")
            for name in ("bootstrap-password", "server-password", "database-password", "admin-password", "upstream-password", "upstream-client-secret"):
                path = directory / name
                if path.exists(): raw = raw.replace(path.read_text().strip(), "[redacted]")
            raw = re.sub(r"[A-Za-z0-9_+/=-]{40,}", "[redacted-value]", raw)
            raw = re.sub(r"([?&](?:code|state|code_challenge)=)[^&\s)\"]+", r"\1[redacted]", raw)
            for line in raw.splitlines():
                if any(marker in line for marker in (" ERROR ", "Caused by:", "APPLICATION FAILED", "Description:", "Reason:", "Action:")):
                    print("APPLICATION_DIAGNOSTIC " + line, flush=True)
            raise
        finally:
            subprocess.run(["docker", "rm", "--force", browser_name], capture_output=True)
            if browser is not None and browser.poll() is None:
                browser.wait(timeout=15)
            operations.compose(environment, "down", "--volumes", "--remove-orphans", "--timeout", "10")


if __name__ == "__main__":
    main()
