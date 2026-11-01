"""Optional real Keycloak fixture with distinct browser sites and scoped CA trust."""

import json
import os
from pathlib import Path
import secrets
import socket


IMAGE = "busystack-keycloak-browser:26.8.1"
REALM = "browser-qualification"
USERNAME = "realm-browser"
INTERNAL_USERNAME = "federated-browser"
SUBJECT = "d0880a2f-bb8e-4f40-84dd-f0707fe08c77"


def configure(root, directory, base, execute, environment):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    issuer = f"https://idp.identity.test:{port}/realms/{REALM}"
    secret = secrets.token_urlsafe(32)
    upstream_password = secrets.token_urlsafe(32)
    secret_file = directory / "upstream-client-secret"
    secret_file.write_text(secret)
    secret_file.chmod(0o444)
    (directory / "upstream-password").write_text(upstream_password)
    (directory / "upstream-password").chmod(0o600)
    realm = {
        "realm": REALM, "enabled": True, "sslRequired": "all", "registrationAllowed": False,
        "roles": {"realm": [{"name": "realm-admin"}]},
        "clients": [{"clientId": "busystack-browser-upstream", "enabled": True, "protocol": "openid-connect",
                     "publicClient": False, "secret": secret, "standardFlowEnabled": True,
                     "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
                     "serviceAccountsEnabled": False, "redirectUris": [base + "/login/oauth2/code/upstream"],
                     "attributes": {"pkce.code.challenge.method": "S256"}}],
        "users": [{"id": SUBJECT, "username": USERNAME, "enabled": True, "realmRoles": ["realm-admin"],
                   "email": "realm-browser@example.test", "emailVerified": True,
                   "firstName": "Federated", "lastName": "Browser", "requiredActions": [],
                   "credentials": [{"type": "password", "value": upstream_password, "temporary": False}]}]
    }
    realm_file = directory / "realm.json"
    realm_file.write_text(json.dumps(realm))
    realm_file.chmod(0o444)
    public_trust = directory / "public-trust"
    public_trust.mkdir(mode=0o700)
    execute([
        "docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{directory / 'ca.pem'}:/input/ca.pem:ro",
        "--volume", f"{directory / 'server-password'}:/input/store-password:ro",
        "--volume", f"{public_trust}:/output", "--entrypoint", "keytool",
        "eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857",
        "-importcert", "-noprompt", "-alias", "fixture-ca", "-file", "/input/ca.pem",
        "-keystore", "/output/trust.p12", "-storetype", "PKCS12", "-storepass:file", "/input/store-password"
    ], environment)
    store = public_trust / "trust.p12"
    store.chmod(0o444)
    # The JVM launcher prints only the @argfile path, not this generated password.
    arguments = directory / "client-trust.args"
    arguments.write_text(
        "-Djavax.net.ssl.trustStore=/tls/client-trust.p12\n"
        "-Djavax.net.ssl.trustStoreType=PKCS12\n"
        "-Djavax.net.ssl.trustStorePassword=" + (directory / "server-password").read_text().strip() + "\n"
    )
    arguments.chmod(0o444)
    configuration = {
        "services": {
            "application": {
                "environment": {
                    "SPRING_APPLICATION_JSON": json.dumps({"busystack": {"oidc": {
                        "enabled": True, "issuer": issuer, "client-id": "busystack-browser-upstream",
                        "allowed-users": [{"subject": SUBJECT, "username": INTERNAL_USERNAME, "display-name": "Federated Browser"}]
                    }}}),
                    "JDK_JAVA_OPTIONS": "@/tls/client-trust.args",
                },
                "volumes": [str(store) + ":/tls/client-trust.p12:ro", str(arguments) + ":/tls/client-trust.args:ro"],
                "secrets": [{"source": "upstream_client_secret", "target": "busystack.oidc.client-secret"}],
            },
            "upstream": {
                "image": IMAGE,
                "command": ["start-dev", "--import-realm", "--hostname=https://idp.identity.test:" + str(port),
                            "--https-port=" + str(port), "--http-enabled=false",
                            "--https-certificate-file=/tls/server.pem", "--https-certificate-key-file=/tls/server.key"],
                "environment": {"JAVA_OPTS_KC_HEAP": "-Xms128m -Xmx512m"},
                "ports": [f"127.0.0.1:{port}:{port}"],
                "volumes": [str(realm_file) + ":/opt/keycloak/data/import/browser-qualification.json:ro",
                            str(directory / "server.pem") + ":/tls/server.pem:ro",
                            str(directory / "server.key") + ":/tls/server.key:ro"],
                "networks": {"default": {"aliases": ["idp.identity.test"]}},
                "mem_limit": "1g", "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"],
            },
        },
        "secrets": {"upstream_client_secret": {"file": str(secret_file)}},
    }
    override = directory / "oidc.override.json"
    override.write_text(json.dumps(configuration))
    return override, issuer
