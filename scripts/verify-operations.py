#!/usr/bin/env python3
"""Isolated packaged-application and database-recovery smoke test.

This does not qualify browser MFA, HTTPS/proxy deployment or CalDAV clients.
Only the two randomly named Compose projects created here are removed.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parent.parent


def execute(command, environment, *, content=None, expected=0):
    result = subprocess.run(
        command,
        cwd=ROOT,
        env=environment,
        input=content,
        text=True,
        capture_output=True,
        timeout=300,
    )
    if result.returncode != expected:
        raise RuntimeError(
            f"Command {command[0:3]} returned {result.returncode}; "
            f"expected {expected}.\n{result.stdout}\n{result.stderr}"
        )
    return result.stdout.strip()


def compose(environment, *arguments, content=None):
    return execute(["docker", "compose", *arguments], environment, content=content)


def sql(environment, statement):
    return compose(
        environment,
        "exec",
        "-T",
        "database",
        "psql",
        "--username",
        "postgres",
        "--dbname",
        "busystack",
        "--no-psqlrc",
        "--tuples-only",
        "--no-align",
        "--set",
        "ON_ERROR_STOP=1",
        content=statement,
    )


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(base, path):
    opener = urllib.request.build_opener(NoRedirect)
    try:
        response = opener.open(base + path, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()


def checks(environment):
    address = compose(environment, "port", "application", "8080").splitlines()[0]
    base = "http://" + address
    for probe in ("readiness", "liveness"):
        status, _, body = request(base, "/actuator/health/" + probe)
        assert status == 200 and json.loads(body)["status"] == "UP"
    status, headers, _ = request(base, "/.well-known/caldav")
    assert status == 308
    assert headers["Location"] == "https://calendar.example.test/dav/"
    status, _, body = request(base, "/.well-known/oauth-authorization-server")
    assert status == 200
    assert json.loads(body)["issuer"] == "https://calendar.example.test"
    status, headers, _ = request(base, "/dav/")
    assert status == 401 and "Basic" in headers.get("WWW-Authenticate", "")
    application = compose(environment, "ps", "--quiet", "application")
    config = json.loads(
        execute(["docker", "inspect", application], environment)
    )[0]
    assert config["Config"]["User"] == "10001:10001"
    assert config["HostConfig"]["ReadonlyRootfs"]
    assert "ALL" in config["HostConfig"]["CapDrop"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="busystack-caldav:local")
    args = parser.parse_args()
    if not args.image or any(character.isspace() for character in args.image):
        parser.error("--image must be a single Docker image reference")
    execute(["docker", "info"], os.environ.copy())
    args.image = execute(["docker", "image", "inspect", "--format", "{{.Id}}", args.image], os.environ.copy())
    print("OPERATIONS_APPLICATION_IMAGE=" + args.image, flush=True)

    projects = []
    with tempfile.TemporaryDirectory(prefix="busystack-operations-") as directory:
        private = Path(directory)
        private.chmod(0o700)
        for name in ("database-password", "admin-password", "bootstrap-password", "recovery-password"):
            secret_file = private / name
            secret_file.write_text(secrets.token_urlsafe(32) + "\n")
            secret_file.chmod(0o600 if name in ("bootstrap-password", "recovery-password") else 0o444)
        override = private / "compose.override.yaml"
        override.write_text(
            "services:\n  application:\n"
            f"    image: {json.dumps(args.image)}\n"
            "    ports: !override\n      - '127.0.0.1::8080'\n"
        )
        environment = os.environ.copy()
        environment.update(
            COMPOSE_FILE=f"{ROOT / 'compose.yaml'}:{override}",
            COMPOSE_PROJECT_NAME="busystack-operations-" + secrets.token_hex(6),
            BUSYSTACK_DATABASE_PASSWORD_FILE=str(private / "database-password"),
            BUSYSTACK_POSTGRES_ADMIN_PASSWORD_FILE=str(private / "admin-password"),
            BUSYSTACK_PUBLIC_BASE_URL="https://calendar.example.test",
            BUSYSTACK_ISSUER="https://calendar.example.test",
            BUSYSTACK_ALLOW_INSECURE_LOCAL="false",
            BUSYSTACK_HTTP_PORT="8080",
            COMPOSE_PATH_SEPARATOR=":",
        )
        try:
            projects.append(environment.copy())
            compose(environment, "up", "-d", "--wait", "--wait-timeout", "120", "database")
            compose(
                environment,
                "run",
                "--rm",
                "--no-deps",
                "--user",
                f"{os.getuid()}:{os.getgid()}",
                "--volume",
                f"{private / 'bootstrap-password'}:/bootstrap-password:ro",
                "application",
                "--spring.main.web-application-type=none",
                "--busystack.bootstrap=true",
                "--busystack.bootstrap-username=opsadmin",
                "--busystack.bootstrap-password-file=/bootstrap-password",
            )
            print("OPERATIONS_SCHEMA_VERSIONS=" + sql(environment, "SELECT string_agg(version,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version IS NOT NULL;"), flush=True)
            content = (
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                "PRODID:-//BusyStack Operations Fixture//EN\r\n"
                "BEGIN:VEVENT\r\nUID:operations-recovery-fixture\r\n"
                "DTSTAMP:20261027T000000Z\r\nDTSTART:20261028T100000Z\r\n"
                "SUMMARY:Recovery verification\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            ).encode()
            checksum = hashlib.sha256(content).hexdigest()
            # These are database-recovery fixtures, not issued protocol credentials.
            sql(
                environment,
                f"""BEGIN;
INSERT INTO accounts(id,username,display_name) VALUES ('22222222-2222-4222-8222-222222222222','opsreader','Operations Reader');
INSERT INTO collections(id,owner_id,name,component,revision) SELECT '11111111-1111-4111-8111-111111111111',id,'Operations fixture','VEVENT',1 FROM accounts WHERE username='opsadmin';
INSERT INTO collection_members(collection_id,account_id,permission) VALUES ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','READ');
INSERT INTO calendar_resources(collection_id,name,uid,content,etag,revision) VALUES ('11111111-1111-4111-8111-111111111111','fixture.ics','operations-recovery-fixture',decode('{content.hex()}','hex'),'"{checksum}"',1);
INSERT INTO collection_changes(collection_id,revision,name,deleted) VALUES ('11111111-1111-4111-8111-111111111111',1,'fixture.ics',false);
INSERT INTO dav_credentials(id,account_id,label,verifier,expires_at) SELECT '33333333-3333-4333-8333-333333333333',id,'Operations fixture',password_hash,now()+interval '1 day' FROM accounts WHERE username='opsadmin';
INSERT INTO oauth2_registered_client(id,client_id,client_name,client_authentication_methods,authorization_grant_types,redirect_uris,scopes,client_settings,token_settings) VALUES ('operations-client','operations-client','Operations database fixture','none','authorization_code,refresh_token','http://127.0.0.1:59621/callback','dav','{{}}','{{}}');
INSERT INTO oauth_refresh_families(authorization_id,account_id,client_id,recovery_epoch,expires_at) SELECT 'operations-family',a.id,'operations-client',s.recovery_epoch,now()+interval '1 day' FROM accounts a CROSS JOIN system_state s WHERE username='opsadmin';
INSERT INTO oauth_refresh_tokens(token_hash,authorization_id,expires_at) VALUES (repeat('a',64),'operations-family',now()+interval '1 day');
INSERT INTO oauth2_authorization(id,registered_client_id,principal_name,authorization_grant_type) SELECT 'operations-grant','operations-client',id::text,'authorization_code' FROM accounts WHERE username='opsadmin';
INSERT INTO oauth2_authorization_consent(registered_client_id,principal_name,authorities) SELECT 'operations-client',id::text,'SCOPE_dav' FROM accounts WHERE username='opsadmin';
INSERT INTO user_entities(id,account_id,name,display_name) SELECT 'b3BlcmF0aW9ucw',id,'opsadmin','Operations Administrator' FROM accounts WHERE username='opsadmin';
INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,public_key_credential_type,backup_state,label) VALUES ('b3BlcmF0aW9ucy1jcmVkZW50aWFs','b3BlcmF0aW9ucw',decode('00','hex'),0,true,false,'public-key',false,'Operations database fixture');
DO $fixture$ BEGIN
  IF to_regclass('public.external_identities') IS NOT NULL THEN
    INSERT INTO external_identities(issuer,subject,account_id,source,last_verified_at) VALUES ('https://identity.example.test/realms/operations','operations-subject','22222222-2222-4222-8222-222222222222','LINKED',now());
  END IF;
END; $fixture$;
DO $fixture$ BEGIN
  IF to_regclass('public.spring_session') IS NOT NULL THEN
    INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES ('44444444-4444-4444-8444-444444444444','55555555-5555-4555-8555-555555555555',(extract(epoch FROM now())*1000)::bigint,(extract(epoch FROM now())*1000)::bigint,1800,(extract(epoch FROM now()+interval '30 minutes')*1000)::bigint,'opsadmin');
    INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES ('44444444-4444-4444-8444-444444444444','operations-database-fixture',decode('00','hex'));
    INSERT INTO account_recovery_codes(id,account_id,token_hash,recovery_epoch,expires_at) SELECT '66666666-6666-4666-8666-666666666666',a.id,repeat('b',64),s.recovery_epoch,now()+interval '1 day' FROM accounts a CROSS JOIN system_state s WHERE username='opsadmin';
    INSERT INTO webauthn_ceremonies(id,session_id,purpose,account_id,security_epoch,recovery_epoch,challenge_hash,expires_at) SELECT '77777777-7777-4777-8777-777777777777','55555555-5555-4555-8555-555555555555','AUTHENTICATE',a.id,a.security_epoch,s.recovery_epoch,repeat('c',64),now()+interval '1 hour' FROM accounts a CROSS JOIN system_state s WHERE username='opsadmin';
    INSERT INTO login_throttle(bucket_key,window_started,attempts,expires_at) VALUES (repeat('d',64),now(),1,now()+interval '1 hour');
  END IF;
END; $fixture$;
DO $fixture$ BEGIN
  IF to_regclass('public.dav_bindings') IS NOT NULL THEN
    INSERT INTO dav_bindings(home_id,path,display_name) SELECT id,'recovery-folder','Recovery folder ü' FROM accounts WHERE username='opsadmin';
    INSERT INTO dav_binding_properties(home_id,path,namespace,name,xml) SELECT id,'recovery-folder','urn:busystack:operations','preserved','<o:preserved xmlns:o="urn:busystack:operations">folder</o:preserved>' FROM accounts WHERE username='opsadmin';
    INSERT INTO dav_collection_properties(collection_id,namespace,name,xml) VALUES ('11111111-1111-4111-8111-111111111111','urn:busystack:operations','preserved','<o:preserved xmlns:o="urn:busystack:operations">collection</o:preserved>');
    INSERT INTO dav_resource_properties(collection_id,resource_name,namespace,name,xml) VALUES ('11111111-1111-4111-8111-111111111111','fixture.ics','urn:busystack:operations','preserved','<o:preserved xmlns:o="urn:busystack:operations">resource</o:preserved>');
  END IF;
END; $fixture$;
DO $fixture$ BEGIN
  IF to_regclass('public.sharing_groups') IS NOT NULL THEN
    INSERT INTO sharing_groups(id,name,created_by) SELECT '88888888-8888-4888-8888-888888888888','Operations group',id FROM accounts WHERE username='opsadmin';
    INSERT INTO sharing_group_members(group_id,account_id) VALUES ('88888888-8888-4888-8888-888888888888','22222222-2222-4222-8222-222222222222');
    INSERT INTO collection_group_members(collection_id,group_id,permission) VALUES ('11111111-1111-4111-8111-111111111111','88888888-8888-4888-8888-888888888888','WRITE');
    INSERT INTO dav_principals(id,kind) VALUES ('99999999-9999-4999-8999-999999999999','GROUP');
  END IF;
END; $fixture$;
COMMIT;
""",
            )
            epoch = sql(environment, "SELECT recovery_epoch FROM system_state WHERE id=1;")
            initial_verifier = sql(environment, "SELECT password_hash FROM accounts WHERE username='opsadmin';")
            has_federation = sql(environment, "SELECT to_regclass('public.external_identities') IS NOT NULL;") == "t"
            has_groups = sql(environment, "SELECT to_regclass('public.sharing_groups') IS NOT NULL;") == "t"
            has_bindings = sql(environment, "SELECT to_regclass('public.dav_bindings') IS NOT NULL;") == "t"
            has_sessions = sql(environment, "SELECT to_regclass('public.spring_session') IS NOT NULL;") == "t"
            account_generation = sql(environment, "SELECT security_epoch FROM accounts WHERE username='opsadmin';") if has_sessions else None
            compose(environment, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "application")
            checks(environment)
            compose(environment, "restart", "application")
            compose(environment, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "application")
            checks(environment)
            archive = private / "recovery.dump"
            execute(["./scripts/backup.sh", str(archive)], environment)
            execute(["./scripts/backup.sh", str(archive)], environment, expected=2)
            compose(environment, "stop", "application")
            execute(["./scripts/restore.sh", str(archive)], environment, expected=2)

            restored = environment.copy()
            restored["COMPOSE_PROJECT_NAME"] += "-restore"
            projects.append(restored)
            compose(restored, "up", "-d", "--wait", "--wait-timeout", "120", "database")
            execute(["./scripts/restore.sh", str(archive)], restored)
            result = json.loads(
                sql(
                    restored,
                    """SELECT json_build_object(
'accounts',(SELECT count(*) FROM accounts),
'enabled_accounts',(SELECT count(*) FROM accounts WHERE enabled),
'owner',(SELECT a.username FROM accounts a JOIN collections c ON c.owner_id=a.id),
'permission',(SELECT permission FROM collection_members),
'content_sha256',(SELECT encode(sha256(content),'hex') FROM calendar_resources),
'epoch',(SELECT recovery_epoch::text FROM system_state),
'credentials',(SELECT count(*) FROM dav_credentials),
'registered_clients',(SELECT count(*) FROM oauth2_registered_client),
'grants',(SELECT count(*) FROM oauth2_authorization),
'consents',(SELECT count(*) FROM oauth2_authorization_consent),
'passkeys',(SELECT count(*) FROM user_credentials),
'active_families',(SELECT count(*) FROM oauth_refresh_families WHERE revoked_at IS NULL),
'history',(SELECT count(*) FROM collection_changes),
'tables_not_owned_by_app',(SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner <> 'busystack'));""",
                )
            )
            assert result["accounts"] == 2 and result["owner"] == "opsadmin"
            assert result["registered_clients"] == 1
            assert result["permission"] == "READ" and result["content_sha256"] == checksum
            assert result["epoch"] != epoch and result["history"] == 1
            for key in ("enabled_accounts", "credentials", "grants", "consents", "passkeys", "active_families", "tables_not_owned_by_app"):
                assert result[key] == 0, key
            if has_federation:
                mapping = sql(restored, "SELECT issuer || '|' || subject || '|' || account_id::text || '|' || (last_verified_at < now() - interval '24 hours')::text FROM external_identities;")
                assert mapping == "https://identity.example.test/realms/operations|operations-subject|22222222-2222-4222-8222-222222222222|true"
            if has_groups:
                groups = json.loads(sql(restored, """SELECT json_build_object(
'name',(SELECT name FROM sharing_groups WHERE id='88888888-8888-4888-8888-888888888888'),
'creator',(SELECT a.username FROM sharing_groups g JOIN accounts a ON a.id=g.created_by WHERE g.id='88888888-8888-4888-8888-888888888888'),
'member',(SELECT a.username FROM sharing_group_members m JOIN accounts a ON a.id=m.account_id WHERE m.group_id='88888888-8888-4888-8888-888888888888'),
'permission',(SELECT permission FROM collection_group_members),
'effective_permission',(SELECT permission FROM collection_effective_members WHERE account_id='22222222-2222-4222-8222-222222222222'),
'retired_principal',(SELECT kind FROM dav_principals WHERE id='99999999-9999-4999-8999-999999999999'));"""))
                assert groups == {'name': 'Operations group', 'creator': 'opsadmin', 'member': 'opsreader', 'permission': 'WRITE', 'effective_permission': 'WRITE', 'retired_principal': 'GROUP'}
            if has_bindings:
                binding_data = json.loads(sql(restored, """SELECT json_build_object(
'bindings',(SELECT count(*) FROM dav_bindings),
'folder',(SELECT display_name FROM dav_bindings WHERE path='recovery-folder'),
'owner_path',(SELECT path FROM dav_bindings b JOIN accounts a ON a.id=b.home_id WHERE a.username='opsadmin' AND b.collection_id='11111111-1111-4111-8111-111111111111'),
'folder_xml',(SELECT xml FROM dav_binding_properties WHERE namespace='urn:busystack:operations'),
'collection_xml',(SELECT xml FROM dav_collection_properties WHERE namespace='urn:busystack:operations'),
'resource_xml',(SELECT xml FROM dav_resource_properties WHERE namespace='urn:busystack:operations'));"""))
                assert binding_data['bindings'] == 3 and binding_data['folder'] == 'Recovery folder ü'
                assert binding_data['owner_path'] == '11111111-1111-4111-8111-111111111111'
                for key, value in [('folder_xml', 'folder'), ('collection_xml', 'collection'), ('resource_xml', 'resource')]:
                    assert binding_data[key] == '<o:preserved xmlns:o="urn:busystack:operations">' + value + '</o:preserved>'
            if has_sessions:
                assert sql(restored, "SELECT (SELECT count(*) FROM spring_session)+(SELECT count(*) FROM spring_session_attributes)+(SELECT count(*) FROM account_recovery_codes)+(SELECT count(*) FROM webauthn_ceremonies)+(SELECT count(*) FROM login_throttle);") == "0"
                assert sql(restored, "SELECT security_epoch FROM accounts WHERE username='opsadmin';") != account_generation
            compose(
                restored,
                "run", "--rm", "--no-deps", "--user", f"{os.getuid()}:{os.getgid()}",
                "--volume", f"{private / 'recovery-password'}:/recovery-password:ro", "application",
                "--spring.main.web-application-type=none", "--busystack.recover-account=true",
                "--busystack.recovery-username=opsadmin", "--busystack.recovery-password-file=/recovery-password",
            )
            assert sql(restored, "SELECT count(*) FROM accounts WHERE enabled;") == "1"
            assert sql(restored, "SELECT enabled FROM accounts WHERE username='opsadmin';") == "t"
            assert sql(restored, "SELECT password_hash FROM accounts WHERE username='opsadmin';") != initial_verifier
            if has_federation:
                external_arguments = ["run", "--rm", "--no-deps", "application",
                    "--spring.main.web-application-type=none", "--busystack.recover-external-account=true",
                    "--busystack.recovery-username=opsreader", "--busystack.recovery-issuer=https://identity.example.test/realms/operations"]
                execute(["docker", "compose", *external_arguments, "--busystack.recovery-subject=wrong-subject"], restored, expected=1)
                assert sql(restored, "SELECT enabled FROM accounts WHERE username='opsreader';") == "f"
                compose(restored, *external_arguments, "--busystack.recovery-subject=operations-subject")
                assert sql(restored, "SELECT enabled AND password_hash IS NULL FROM accounts WHERE username='opsreader';") == "t"
                assert sql(restored, "SELECT count(*) FROM accounts WHERE enabled;") == "2"
            compose(restored, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240", "application")
            checks(restored)
            print("PASS: packaged startup, non-root/read-only runtime, probes, configured discovery/issuer, DAV challenge, restart, database backup/restore, data/ownership/share preservation, restored credential/grant invalidation, controlled local/external account recovery (including rejected external subject mismatch).")
            print("Not qualified here: interactive authentication, HTTP calendar/task content operations, HTTPS/proxy paths or independent clients.")
        finally:
            for created in reversed(projects):
                compose(created, "down", "--volumes", "--remove-orphans", "--timeout", "10")


if __name__ == "__main__":
    main()
