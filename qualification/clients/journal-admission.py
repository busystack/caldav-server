#!/usr/bin/env python3
"""Independent storage-admission test against a dedicated CalDAV fixture account."""

import argparse
import base64
from collections import Counter
import hashlib
import http.client
import json
from pathlib import Path
import re
import ssl
from urllib.parse import urljoin, urlsplit
from uuid import uuid4
from xml.etree import ElementTree as ET

D = '{DAV:}'
C = '{urn:ietf:params:xml:ns:caldav}'
FIXTURE = Path(__file__).resolve().parents[1] / 'fixtures/vjournal/admission-v1.json'
XML = {'Content-Type': 'application/xml; charset=utf-8'}
ICAL = {'Content-Type': 'text/calendar; charset=utf-8'}


class Dav:
    def __init__(self, endpoint, username, password, ca_file):
        self.endpoint = endpoint
        origin = urlsplit(endpoint)
        if origin.scheme != 'https' and not (
            origin.scheme == 'http' and origin.hostname == '127.0.0.1'
        ):
            raise ValueError('Fixture endpoint requires HTTPS or IPv4 loopback HTTP')
        self.origin = origin.scheme, origin.netloc
        self.authorization = 'Basic ' + base64.b64encode(
            f'{username}:{password}'.encode()).decode('ascii')
        self.context = ssl.create_default_context(cafile=ca_file)

    def request(self, method, reference, body=None, headers=None):
        uri = urlsplit(urljoin(self.endpoint, reference))
        if (uri.scheme, uri.netloc) != self.origin or uri.query or uri.fragment:
            raise ValueError('Unexpected DAV fixture href')
        if uri.scheme == 'https':
            client = http.client.HTTPSConnection(uri.netloc, timeout=20, context=self.context)
        else:
            client = http.client.HTTPConnection(uri.netloc, timeout=20)
        try:
            client.request(method, uri.path, body, {
                'Authorization': self.authorization, **(headers or {})
            })
            response = client.getresponse()
            return response.status, response.headers, response.read()
        finally:
            client.close()

    def properties(self, href, fields, depth=0):
        body = ('<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">'
                f'<d:prop>{fields}</d:prop></d:propfind>')
        status, _, content = self.request('PROPFIND', href, body, {**XML, 'Depth': str(depth)})
        if status != 207:
            raise RuntimeError(f'Fixture PROPFIND failed with HTTP {status}')
        result = {}
        for response in ET.fromstring(content).findall(f'{D}response'):
            properties = {}
            for propstat in response.findall(f'{D}propstat'):
                if (propstat.findtext(f'{D}status') or '').split()[1:2] == ['200']:
                    properties.update({child.tag: child for child in propstat.find(f'{D}prop')})
            result[response.findtext(f'{D}href')] = properties
        return result


def representation(uid, case, summary=None):
    lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//BusyStack//Journal QA//EN',
             *case.get('calendar', []), 'BEGIN:VJOURNAL', f'UID:{uid}',
             'DTSTAMP:20261125T080000Z']
    if summary:
        lines.append(f'SUMMARY:{summary}')
    lines += [*case['journal'], 'END:VJOURNAL', 'END:VCALENDAR']
    folded = ['\r\n '.join([line[:75], *[line[i:i + 74] for i in range(75, len(line), 74)]])
              for line in lines]
    return ('\r\n'.join(folded) + '\r\n').encode('ascii')


def semantic_lines(body):
    # The admission corpus uses ASCII values without ambiguous escaping. Retain each
    # complete property, parameter list and component path while ignoring folding/order.
    path = []
    properties = Counter()
    unfolded = re.sub(rb'\r?\n[ \t]', b'', body)
    for line in unfolded.splitlines():
        if line.startswith(b'BEGIN:'):
            path.append(line[6:])
            properties[tuple(path), b'BEGIN'] += 1
        elif line.startswith(b'END:'):
            if not path or path.pop() != line[4:]:
                raise ValueError('Malformed readback component structure')
        else:
            properties[tuple(path), line] += 1
    if path:
        raise ValueError('Unclosed readback component')
    return properties


def error_names(body):
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return []
    return [child.tag for child in root] if root.tag == D + 'error' else []


def probe(dav, collection, case, conditional):
    case_id = case['id']
    suffix = 'update' if conditional else 'create'
    href = collection + f'{case_id}-{suffix}.ics'
    uid = f'{case_id}-{suffix}-fixture'
    before = None
    if conditional:
        status, _, _ = dav.request('PUT', href, representation(uid, {'journal': []}),
                                   {**ICAL, 'If-None-Match': '*'})
        if status != 201:
            raise RuntimeError(f'Conditional PUT setup failed with HTTP {status}')
        before = dav.request('GET', href)
        if before[0] != 200 or not before[1].get('ETag') or before[1]['ETag'].startswith('W/'):
            raise RuntimeError('Fixture did not return a strong baseline ETag')
    expected = representation(uid, case, 'Updated fixture' if conditional else None)
    condition = {'If-Match': before[1]['ETag']} if conditional else {'If-None-Match': '*'}
    fields = '<d:getetag/><d:sync-token/>'
    inventory_before = dav.properties(collection, fields, 1)
    status, _, response = dav.request('PUT', href, expected, {**ICAL, **condition})
    read_status, read_headers, read_body = dav.request('GET', href)
    if status in (200, 201, 204):
        issue = None if (
            read_status == 200 and read_headers.get('ETag')
            and semantic_lines(read_body) == semantic_lines(expected)
        ) else 'authoritative readback mismatch'
    else:
        inventory_after = dav.properties(collection, fields, 1)
        snapshot = lambda inventory: {
            name: {key: prop.text for key, prop in props.items()}
            for name, props in inventory.items()
        }
        same_inventory = snapshot(inventory_before) == snapshot(inventory_after)
        same_resource = (
            read_status == 200 and read_body == before[2]
            and read_headers.get('ETag') == before[1]['ETag']
        ) if before else read_status == 404
        issue = ('admission rejected; original state unchanged' if same_inventory and same_resource
                 else 'admission rejected; original state changed')
    print(json.dumps({'case': case_id, 'operation': suffix, 'put': status, 'get': read_status,
                      'dav_errors': error_names(response),
                      'response_sha256': hashlib.sha256(response).hexdigest(),
                      'issue': issue}), flush=True)
    return issue is None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('endpoint', help='CalDAV service URL of a disposable fixture account')
    parser.add_argument('username')
    parser.add_argument('password_file', type=Path)
    parser.add_argument('--ca-file', help='PEM trust anchor for the fixture endpoint')
    args = parser.parse_args()
    dav = Dav(args.endpoint, args.username, args.password_file.read_text().strip(), args.ca_file)
    principal, = dav.properties(args.endpoint, '<d:current-user-principal/>').values()
    principal_href = principal[D + 'current-user-principal'].findtext(D + 'href')
    home, = dav.properties(principal_href, '<c:calendar-home-set/>').values()
    home_href = home[C + 'calendar-home-set'].findtext(D + 'href')
    collection = home_href.rstrip('/') + '/journal-admission-' + uuid4().hex + '/'
    body = ('<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">'
            '<d:set><d:prop><d:displayname>Journal admission fixture</d:displayname>'
            '<c:supported-calendar-component-set><c:comp name="VJOURNAL"/>'
            '</c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>')
    status, _, _ = dav.request('MKCALENDAR', collection, body, XML)
    if status != 201:
        raise RuntimeError(f'Fixture MKCALENDAR failed with HTTP {status}')
    try:
        caps, = dav.properties(collection, '<c:supported-calendar-component-set/>'
                                           '<d:current-user-privilege-set/>').values()
        components = [tag.get('name') for tag in caps[C + 'supported-calendar-component-set']]
        if components != ['VJOURNAL'] or D + 'current-user-privilege-set' not in caps:
            raise RuntimeError('Fixture did not discover a dedicated journal collection')
        status, _, _ = dav.request('PUT', collection + 'target.ics',
                                   representation('target-fixture', {'journal': []}),
                                   {**ICAL, 'If-None-Match': '*'})
        if status != 201:
            raise RuntimeError(f'UID reference target creation failed with HTTP {status}')
        fixture_bytes = FIXTURE.read_bytes()
        fixtures = json.loads(fixture_bytes)
        print(json.dumps({'fixture_version': fixtures['version'],
                          'fixture_sha256': hashlib.sha256(fixture_bytes).hexdigest()}), flush=True)
        results = [probe(dav, collection, case, conditional)
                   for case in fixtures['cases'] for conditional in (False, True)]
        print(json.dumps({'executed': len(results), 'passed': sum(results),
                          'failed': len(results) - sum(results)}), flush=True)
        return 0 if all(results) else 1
    finally:
        status, _, _ = dav.request('DELETE', collection)
        if status not in (200, 204):
            raise RuntimeError(f'Fixture cleanup failed with HTTP {status}')


if __name__ == '__main__':
    raise SystemExit(main())
