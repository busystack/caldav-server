"""Real HTTPS recovery checkpoint; state and credentials stay in the private fixture directory."""

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as XML
import tls
import workload_measurement


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Checkpoint:
    def __init__(self, root, directory, operations, base, oidc, issuer, environments):
        self.root, self.directory, self.operations, self.base = root, directory, operations, base
        self.oidc, self.issuer, self.environments = oidc, issuer, environments
        self.ca = directory / 'ca.pem'
        self.exchange = directory / 'recovery.json'
        self.attachments = []
        self.workload = None
        self.content = ('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BusyStack independent recovery fixture//EN\r\n'
            'BEGIN:VTODO\r\nUID:https-recovery-fixture\r\nDTSTAMP:20261027T000000Z\r\n'
            'SUMMARY:Restored task ✓\r\nSTATUS:IN-PROCESS\r\nPRIORITY:5\r\nPERCENT-COMPLETE:25\r\n'
            'X-RESTORE-PRESERVED;X-ORIGIN=independent:opaque client data\r\nEND:VTODO\r\nEND:VCALENDAR\r\n').encode()

    def request(self, path, method='GET', headers=None, body=None):
        request = urllib.request.Request(self.base + path, method=method, headers=headers or {}, data=body)
        try:
            response = tls.opener(self.ca, NoRedirect).open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(1048577)
            assert len(data) <= 1048576, 'Recovery response exceeded its limit'
            return response.status, response.headers, data

    def report(self, encoded, token=None):
        request = XML.Element('{DAV:}sync-collection')
        XML.SubElement(request, '{DAV:}sync-token').text = token
        XML.SubElement(request, '{DAV:}sync-level').text = '1'
        XML.SubElement(XML.SubElement(request, '{DAV:}prop'), '{DAV:}getetag')
        return self.request(self.collection_path, 'REPORT',
            {'Authorization': 'Basic ' + encoded, 'Content-Type': 'application/xml', 'Depth': '0'}, XML.tostring(request))

    def backup(self, environment):
        self.saved = json.loads(self.exchange.read_text())
        username = self.saved['username'].replace("'", "''")
        self.owner = self.operations.sql(environment, "SELECT id FROM accounts WHERE username='" + username + "';")
        self.collection_path = '/dav/calendars/' + self.owner + '/' + self.saved['collection'] + '/'
        self.resource_path = self.collection_path + 'restoration-fixture.ics'
        basic = {'Authorization': 'Basic ' + self.saved['encoded']}
        status, _, _ = self.request(self.resource_path, 'PUT',
            {**basic, 'Content-Type': 'text/calendar;charset=utf-8', 'If-None-Match': '*'}, self.content)
        assert status == 201, 'Recovery VTODO creation failed'
        status, _, data = self.request(self.resource_path, headers=basic)
        assert status == 200 and data == self.content
        status, _, data = self.report(self.saved['encoded'])
        assert status == 207
        document = XML.fromstring(data)
        self.token = document.findtext('{DAV:}sync-token')
        assert self.token and any(value.text and value.text.endswith('/restoration-fixture.ics') for value in document.findall('.//{DAV:}href'))
        assert self.request(self.resource_path, headers={'Authorization': 'Bearer ' + self.saved['grant']['accessToken']})[0] == 200
        self.reader = self.operations.sql(environment, "SELECT id FROM accounts WHERE username='" + self.saved['readerUsername'] + "';")
        self.writer = self.operations.sql(environment, "SELECT id FROM accounts WHERE username='" + self.saved['writerUsername'] + "';")
        self.reader_path = '/dav/calendars/' + self.reader + '/' + self.saved['collection'] + '/restoration-fixture.ics'
        self.permissions(environment)
        self.epoch = self.operations.sql(environment, 'SELECT recovery_epoch FROM system_state WHERE id=1;')
        if self.workload is not None:
            workload_measurement.observe(self, environment, self.workload['python'], self.workload['output'])
        # Benchmarking must not turn the revoked-credential proof into an accidental expiry test.
        assert self.request(self.resource_path, headers=basic)[0] == 200, 'Snapshot DAV credential expired before backup'
        assert self.request(self.resource_path, headers={'Authorization': 'Bearer ' + self.saved['grant']['accessToken']})[0] == 200, 'Snapshot access token expired before backup; issue a fresh grant before qualification'
        self.archive = self.directory / 'https-recovery.dump'
        self.operations.execute(['./scripts/backup.sh', str(self.archive)], environment)
        print('HTTPS_RECOVERY_SNAPSHOT_WITH_VALID_DAV_OAUTH_AND_SYNC_CREATED', flush=True)

    def restore(self, environment):
        source = environment.copy()
        self.operations.compose(source, 'stop', 'application', 'reverse-proxy')
        restored = source.copy()
        restored['COMPOSE_PROJECT_NAME'] += '-restored'
        self.environments.append(restored)
        self.operations.compose(restored, 'up', '-d', '--wait', '--wait-timeout', '120', 'database')
        if self.oidc is not None:
            # Keep the existing independent provider running; connect only our fixture container/network.
            upstream = self.operations.compose(source, 'ps', '--quiet', 'upstream')
            network = restored['COMPOSE_PROJECT_NAME'] + '_default'
            metadata = json.loads(self.operations.execute(['docker', 'inspect', upstream], source))[0]
            assert metadata['Config']['Labels']['com.docker.compose.project'] == source['COMPOSE_PROJECT_NAME']
            graph = json.loads(self.operations.execute(['docker', 'network', 'inspect', network], source))[0]
            assert graph['Labels']['com.docker.compose.project'] == restored['COMPOSE_PROJECT_NAME']
            self.operations.execute(['docker', 'network', 'connect', '--alias', 'idp.identity.test', network, upstream], source)
            self.attachments.append((network, upstream))
        self.operations.execute(['./scripts/restore.sh', str(self.archive)], restored)
        assert self.operations.sql(restored, 'SELECT count(*) FROM accounts WHERE enabled;') == '0'
        for table in ['dav_credentials', 'oauth2_authorization', 'oauth2_authorization_consent',
            'spring_session', 'user_credentials', 'account_recovery_codes', 'webauthn_ceremonies']:
            assert self.operations.sql(restored, 'SELECT count(*) FROM ' + table + ';') == '0', table
        assert self.operations.sql(restored, 'SELECT recovery_epoch FROM system_state WHERE id=1;') != self.epoch
        assert self.operations.sql(restored, "SELECT encode(sha256(content),'hex') FROM calendar_resources WHERE name='restoration-fixture.ics';") == hashlib.sha256(self.content).hexdigest()
        assert self.operations.sql(restored, "SELECT owner_id FROM collections WHERE id='" + self.saved['collection'] + "';") == self.owner
        self.permissions(restored)
        # The fixture identities/grants are known and reviewed; recover exactly the selected user.
        if self.oidc is None:
            self.operations.compose(restored, 'run', '--rm', '--no-deps', '--user', self.operator,
                '--volume', str(self.directory / 'restore-password') + ':/recovery-password:ro', 'application',
                '--spring.main.web-application-type=none', '--busystack.recover-account=true',
                '--busystack.recovery-username=' + self.saved['username'], '--busystack.recovery-password-file=/recovery-password')
        else:
            self.operations.compose(restored, 'run', '--rm', '--no-deps', 'application',
                '--spring.main.web-application-type=none', '--busystack.recover-external-account=true',
                '--busystack.recovery-username=' + self.saved['username'], '--busystack.recovery-issuer=' + self.issuer,
                '--busystack.recovery-subject=' + self.oidc.SUBJECT)
        self.operations.compose(restored, 'run', '--rm', '--no-deps', '--user', self.operator,
            '--volume', str(self.directory / 'reader-restore-password') + ':/recovery-password:ro', 'application',
            '--spring.main.web-application-type=none', '--busystack.recover-account=true',
            '--busystack.recovery-username=' + self.saved['readerUsername'], '--busystack.recovery-password-file=/recovery-password')
        assert self.operations.sql(restored, 'SELECT count(*) FROM accounts WHERE enabled;') == '2'
        assert self.operations.sql(restored, "SELECT enabled FROM accounts WHERE id='" + self.writer + "';") == 'f'
        self.operations.compose(restored, 'up', '-d', '--no-build', 'application')
        self.operations.compose(restored, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240', 'reverse-proxy')
        return restored

    def permissions(self, environment):
        for account, permission in [(self.reader, 'READ'), (self.writer, 'WRITE')]:
            assert self.operations.sql(environment, "SELECT permission FROM collection_members WHERE collection_id='"
                + self.saved['collection'] + "' AND account_id='" + account + "';") == permission

    @property
    def operator(self):
        import os
        return str(os.getuid()) + ':' + str(os.getgid())

    def verify(self):
        current = json.loads(self.exchange.read_text())
        assert self.request(self.resource_path, headers={'Authorization': 'Basic ' + self.saved['encoded']})[0] == 401
        assert time.time() * 1000 < self.saved['grant']['accessExpiresAt'], 'Restore access-token proof exceeded natural expiry; rerun with fresh credentials'
        assert self.request(self.resource_path, headers={'Authorization': 'Bearer ' + self.saved['grant']['accessToken']})[0] == 401
        status, _, data = self.request('/oauth2/token', 'POST', {'Content-Type': 'application/x-www-form-urlencoded'},
            urllib.parse.urlencode({'grant_type': 'refresh_token', 'client_id': self.saved['grant']['clientId'],
                'refresh_token': self.saved['grant']['refreshToken']}).encode())
        assert status == 400 and json.loads(data)['error'] == 'invalid_grant'
        status, _, data = self.request(self.resource_path, headers={'Authorization': 'Basic ' + current['recoveredEncoded']})
        assert status == 200 and data == self.content
        status, _, data = self.report(current['recoveredEncoded'], self.token)
        assert status == 403 and XML.fromstring(data).find('{DAV:}valid-sync-token') is not None
        status, _, data = self.report(current['recoveredEncoded'])
        assert status == 207
        report = XML.fromstring(data)
        assert report.findtext('{DAV:}sync-token') and report.findtext('{DAV:}sync-token') != self.token
        assert any(value.text and value.text.endswith('/restoration-fixture.ics') for value in report.findall('.//{DAV:}href'))
        reader = {'Authorization': 'Basic ' + current['readerEncoded']}
        status, headers, data = self.request(self.reader_path, headers=reader)
        assert status == 200 and data == self.content, 'Restored READ share must serve the authoritative task'
        changed = self.content.replace(b'PERCENT-COMPLETE:25', b'PERCENT-COMPLETE:50')
        assert self.request(self.reader_path, 'PUT', {**reader, 'Content-Type': 'text/calendar',
            'If-Match': headers['ETag']}, changed)[0] == 403, 'Restored READ recipient must not write'
        assert self.request(self.resource_path, headers={'Authorization': 'Basic ' + current['recoveredEncoded']})[2] == self.content
        print('HTTPS_RECOVERY_RESTORED_READ_SHARE_GET_ALLOWED_PUT_DENIED', flush=True)
        print('HTTPS_RECOVERY_OLD_CREDENTIALS_AND_SYNC_REJECTED_FRESH_ACCESS_PRESERVES_TASK', flush=True)

    def verify_revoked_reader(self):
        current = json.loads(self.exchange.read_text())
        assert self.request(self.reader_path, headers={'Authorization': 'Basic ' + current['readerEncoded']})[0] in (403, 404)
        status, _, content = self.request(self.resource_path, headers={'Authorization': 'Basic ' + current['recoveredEncoded']})
        assert status == 200 and content == self.content
        print('HTTPS_RECOVERY_REVOKED_READER_DENIED_OWNER_TASK_PRESERVED', flush=True)

    def disconnect(self, environment):
        for network, upstream in reversed(self.attachments):
            self.operations.execute(['docker', 'network', 'disconnect', network, upstream], environment)
