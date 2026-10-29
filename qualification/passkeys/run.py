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
import recovery
import sharing_capacity


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("operations", ROOT / "scripts/verify-operations.py")
operations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operations)


def ready(base, ca, public_proxy=False, check_path=None, timeout=240):
    deadline = time.monotonic() + timeout
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
    raise RuntimeError("Packaged TLS endpoint did not become ready within " + str(timeout) + " seconds")


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
    parser.add_argument("--application-image", default="busystack-caldav:0.0.1")
    parser.add_argument("--browser-image", default="busystack-passkey-browser:qualification")
    parser.add_argument("--context-path", choices=("", "/team"), default="")
    parser.add_argument("--proxy", action="store_true", help="Use pinned Nginx HTTPS termination and the /team prefix")
    parser.add_argument("--oidc", action="store_true", help="Also qualify a real Keycloak login across distinct reserved test sites")
    parser.add_argument("--restore", action="store_true", help="Qualify actual HTTPS authentication, content and sync after restoring an older database")
    parser.add_argument("--groups", action="store_true", help="Require the V9 group-management and owner-sharing browser workflow")
    parser.add_argument("--workload", action="store_true", help="Measure the bounded independent-client workload during local HTTPS restoration")
    parser.add_argument("--clients", action="store_true", help="Run existing Python/native Thunderbird correctness profiles on the packaged server, without performance workload")
    parser.add_argument("--client-python", default=os.environ.get("BUSYSTACK_CLIENT_PYTHON", str(ROOT / ".client-venv/bin/python")))
    parser.add_argument("--workload-output", default=str(ROOT / "target/qualification/workload.json"))
    parser.add_argument("--strict-sync-depth", action="store_true", help="Explicit false override; default qualification leaves the setting absent")
    parser.add_argument("--thunderbird-binary", default=os.environ.get("BUSYSTACK_THUNDERBIRD_BINARY", str(ROOT / ".client-tools/thunderbird/thunderbird")))
    args = parser.parse_args()
    if args.clients and (not args.restore or args.oidc or args.strict_sync_depth):
        parser.error("--clients requires local --restore and the default synchronization compatibility profile")
    if args.clients and (not Path(args.client_python).is_file() or not os.access(args.thunderbird_binary, os.X_OK)):
        parser.error("--clients requires the pinned Python environment and native Thunderbird binary")
    if args.workload and args.strict_sync_depth:
        parser.error("Independent client workload requires the default sync accommodation; strict override has its own protocol checks")
    if args.workload and (not args.restore or args.oidc):
        parser.error("--workload requires the local --restore profile")
    if args.workload and not Path(args.client_python).is_file():
        parser.error("--workload requires the pinned independent-client Python environment")
    if args.workload and not os.access(args.thunderbird_binary, os.X_OK):
        parser.error("--workload requires the pinned native Thunderbird binary; use install-thunderbird.sh or --thunderbird-binary")
    if args.restore:
        args.proxy = True
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
    print("BROWSER_GROUP_WORKFLOW=" + ("required" if args.groups else "unexecuted"), flush=True)
    print("HTTPS_RESTORE_WORKFLOW=" + ("required" if args.restore else "unexecuted"), flush=True)
    print("DAV_SYNC_DEPTH_INPUT=" + ("explicit false strict override" if args.strict_sync_depth else "setting absent; Compose/application default"), flush=True)
    with tempfile.TemporaryDirectory(prefix="busystack-browser-") as temporary:
        directory = Path(temporary)
        directory.chmod(0o700)
        for name in ("database-password", "admin-password", "bootstrap-password", "restore-password", "reader-restore-password"):
            path = directory / name
            path.write_text(secrets.token_urlsafe(32) + "\n")
            path.chmod(0o600 if name in ("bootstrap-password", "restore-password", "reader-restore-password") else 0o444)
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
        environment.pop("BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE", None)
        if args.strict_sync_depth:
            environment["BUSYSTACK_DAV_ALLOW_SYNC_DEPTH_ONE"] = "false"
        issuer = None
        if args.oidc:
            operations.execute(["docker", "image", "inspect", oidc.IMAGE], environment)
            upstream_override, issuer = oidc.configure(ROOT, directory, base, operations.execute, environment)
            environment["COMPOSE_FILE"] += ":" + str(upstream_override)
        environments = [environment.copy()]
        checkpoint = recovery.Checkpoint(ROOT, directory, operations, base, oidc if args.oidc else None, issuer, environments) if args.restore else None
        if checkpoint is not None:
            if args.clients:
                checkpoint.clients = {"python": args.client_python, "thunderbird": args.thunderbird_binary,
                    "image": args.application_image}
            checkpoint.exchange.touch(mode=0o600)
            if args.workload:
                checkpoint.workload = {"python": args.client_python, "output": args.workload_output, "thunderbird": args.thunderbird_binary}
        browser = None
        sharing_load = None
        try:
            operations.compose(environment, "up", "-d", "--wait", "--wait-timeout", "120", "database")
            if args.oidc:
                operations.compose(environment, "up", "-d", "--no-build", "upstream")
                # The application may resolve trusted issuer metadata at startup; establish the
                # real provider readiness condition first, rather than depending on restart timing.
                ready(issuer, directory / "ca.pem", False, "/.well-known/openid-configuration", timeout=480)
                with tls.opener(directory / "ca.pem").open(issuer + "/.well-known/openid-configuration", timeout=10) as response:
                    assert json.load(response)["issuer"] == issuer
                print("UPSTREAM_HTTPS_METADATA_READY", flush=True)
            operations.compose(environment, "run", "--rm", "--no-deps", "--user", f"{os.getuid()}:{os.getgid()}", "--volume", f"{directory / 'bootstrap-password'}:/bootstrap-password:ro", "application", "--spring.main.web-application-type=none", "--busystack.bootstrap=true", "--busystack.bootstrap-username=browseradmin", "--busystack.bootstrap-password-file=/bootstrap-password")
            operations.compose(environment, "up", "-d", "--no-build", "application")
            if args.proxy:
                operations.compose(environment, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "reverse-proxy")
            ready(base, directory / "ca.pem", args.proxy)
            tls.verify_profile(base, directory / "ca.pem")
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
            browser_command += ["--env", "BUSYSTACK_SYNC_DEPTH_INPUT=" + ("explicit false strict override" if args.strict_sync_depth else "setting absent; Compose/application default"), "--env", "BUSYSTACK_SYNC_DEPTH_EXPECTED=" + ("false" if args.strict_sync_depth else "true")]
            if args.workload:
                browser_command += ["--env", "BUSYSTACK_BROWSER_CAPACITY=true"]
            if args.groups:
                browser_command += ["--env", "BUSYSTACK_BROWSER_GROUPS=true"]
            if checkpoint is not None:
                browser_command += ["--env", "BUSYSTACK_BROWSER_RESTORE=true", "--env", "BUSYSTACK_BROWSER_RECOVERY_FILE=/fixture/recovery.json",
                    "--env", "BUSYSTACK_BROWSER_RECOVERY_PASSWORD_FILE=/fixture/restore-password",
                    "--env", "BUSYSTACK_BROWSER_READER_PASSWORD_FILE=/fixture/reader-restore-password",
                    "--volume", f"{directory / 'reader-restore-password'}:/fixture/reader-restore-password:ro",
                    "--volume", f"{checkpoint.exchange}:/fixture/recovery.json:rw", "--volume", f"{directory / 'restore-password'}:/fixture/restore-password:ro"]
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
            deadline = time.monotonic() + (1200 if args.restore else 600)
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
                    if line == "READY_CAPACITY_SHARING" and checkpoint is not None and args.workload:
                        sharing_load = sharing_capacity.SharingCapacity(checkpoint, environment)
                        sharing_load.seed()
                        browser.stdin.write(b"CAPACITY_SHARING_SEEDED\n")
                        browser.stdin.flush()
                    elif line == "READY_CAPACITY_SHARING_CLEANUP" and sharing_load is not None:
                        sharing_load.finish()
                        browser.stdin.write(b"CAPACITY_SHARING_CLEANED\n")
                        browser.stdin.flush()
                    elif line == "READY_RESTORE_BACKUP" and checkpoint is not None:
                        checkpoint.backup(environment)
                        browser.stdin.write(b"BACKED_UP\n")
                        browser.stdin.flush()
                    elif line == "READY_RESTORE_APPLY" and checkpoint is not None:
                        environment = checkpoint.restore(environment)
                        ready(base, directory / "ca.pem", True)
                        browser.stdin.write(b"RESTORED\n")
                        browser.stdin.flush()
                    elif line == "READY_RESTORE_VERIFY" and checkpoint is not None:
                        checkpoint.verify()
                        browser.stdin.write(b"RESTORE_VERIFIED\n")
                        browser.stdin.flush()
                    elif line == "READY_RESTORE_REVOKED_READER_VERIFY" and checkpoint is not None:
                        checkpoint.verify_revoked_reader()
                        browser.stdin.write(b"RESTORE_REVOKED_READER_VERIFIED\n")
                        browser.stdin.flush()
                    elif line == "READY_RESTART":
                        assert int(operations.sql(environment, "SELECT count(*) FROM user_credentials;")) == (2 if args.restore else 2 if args.oidc else 1)
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
            for expected in ("PASSWORD_PLUS_WEBAUTHN_AND_MUTATION_PASSED", "BROWSER_SINGLE_USE_PRIMARY_PLUS_RECOVERY_CODE_RESET_AND_FRESH_MFA_PASSED", "BROWSER_DAV_CREDENTIAL_ISSUANCE_DISPLAY_ONCE_AND_REVOCATION_PASSED", "BROWSER_S256_CONSENT_TOKEN_REFRESH_AND_GRANT_REVOCATION_PASSED", "BROWSER_CONSENT_WITHOUT_CSRF_REJECTED", "PERSISTED_PASSKEY_AFTER_RESTART_PASSED", "DISABLED_SESSION_AND_PASSKEY_PASSED"):
                assert expected in signals, expected
            if args.oidc:
                assert "CROSS_SITE_ORGANIZATION_PLUS_PASSKEY_AND_NONADMIN_PROVISIONING_PASSED" in signals
            if args.groups:
                assert "BROWSER_GROUP_MEMBERSHIP_UPDATE_DELETE_AND_OWNER_GRANTS_PASSED" in signals
            if args.restore:
                assert "HTTPS_RESTORE_OLD_ACCESS_REJECTED_FRESH_MFA_DAV_OAUTH_CONTENT_AND_SYNC_PASSED" in signals
                assert "HTTPS_RESTORE_SHARED_RECIPIENT_FRESH_MFA_READ_WRITE_DENIAL_AND_REVOCATION_PASSED" in signals
            print("PASS: trusted test-CA HTTPS, Chromium virtual CTAP2 enrollment/assertion, password+passkey MFA enforcement, session rotation, persistence across restart, disabled-session/passkey denial.")
        except Exception:
            # Emit only sanitized error diagnostics from this disposable application, before cleanup.
            try:
                raw = operations.compose(environment, "logs", "--no-log-prefix", "--tail", "120", "application")
            except (RuntimeError, subprocess.TimeoutExpired):
                raw = ""
                print("APPLICATION_DIAGNOSTICS_UNAVAILABLE", flush=True)
            for name in ("bootstrap-password", "restore-password", "reader-restore-password", "server-password", "database-password", "admin-password", "upstream-password", "upstream-client-secret"):
                path = directory / name
                if path.exists():
                    value = path.read_text().strip()
                    if value: raw = raw.replace(value, "[redacted]")
            raw = re.sub(r"[A-Za-z0-9_+/=-]{40,}", "[redacted-value]", raw)
            raw = re.sub(r"([?&](?:code|state|code_challenge)=)[^&\s)\"]+", r"\1[redacted]", raw)
            for line in raw.splitlines():
                if any(marker in line for marker in (" ERROR ", "Caused by:", "APPLICATION FAILED", "Description:", "Reason:", "Action:")):
                    print("APPLICATION_DIAGNOSTIC " + line, flush=True)
            raise
        finally:
            if sharing_load is not None:
                sharing_load.stop.set()
                if hasattr(sharing_load, "thread"):
                    sharing_load.thread.join(timeout=12)
            try:
                subprocess.run(["docker", "rm", "--force", browser_name], capture_output=True)
                if browser is not None and browser.poll() is None:
                    browser.wait(timeout=15)
            finally:
                try:
                    if checkpoint is not None: checkpoint.disconnect(environment)
                finally:
                    cleanup_errors = []
                    for created in reversed(environments):
                        try:
                            operations.compose(created, "down", "--volumes", "--remove-orphans", "--timeout", "10")
                        except (RuntimeError, subprocess.TimeoutExpired) as failure:
                            cleanup_errors.append(failure)
                    if cleanup_errors:
                        raise RuntimeError("A disposable qualification project could not be cleaned up") from cleanup_errors[0]


if __name__ == "__main__":
    main()
