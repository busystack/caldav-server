# Stock Nextcloud journal storage admission

On 2026-11-26, the independent loopback DAV probe used official Nextcloud
35.0.3 (internal version 35.0.3.2), PHP 8.5.13, SabreDAV 4.7.0 and
Sabre VObject 4.5.6 on a disposable SQLite installation. The official
`nextcloud:35.0.3-apache` manifest digest was
`sha256:f44180601f18e7664557232b4c5edb0eb4a82d649c05b93313f4e15909553d42`.
The running image ID was
`sha256:7d7c1ecc23d6d3848a44b1502cd7011082d1299466ad169de758aadf74fbb555`.

The fixture program discovered the principal and home, created and discovered
a VJOURNAL-only collection, and verified create, conditional replace, and
authoritative GET for 11 cases. It exited 1, with 12 passing operations and
10 rejected operations. Every rejected write left the prior resource state,
inventory and sync token unchanged. The program removed its test collection.
[Sanitized results](vjournal-nextcloud-35.0.3.jsonl) record both HTTP statuses,
the error element names and the response hash.

| Fixture | Source | Observed create / replace |
| --- | --- | --- |
| J01 minimal undated | [RFC 5545 §3.6.3](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.6.3) | 201 / 204 and complete GET |
| J10 colour, image, wrapper, unknown parameter | [RFC 7986 §§4–5](https://www.rfc-editor.org/rfc/rfc7986.html#section-4) | 201 / 204 and complete GET |
| J11 structured, styled and nested | [RFC 9073 §§4–7](https://www.rfc-editor.org/rfc/rfc9073.html#section-4) | 201 / 204 and complete GET |
| J12 LINK URI, legacy and URI RELATED-TO | [RFC 9253 §§7, 9.1](https://www.rfc-editor.org/rfc/rfc9253.html#section-7) | 201 / 204 and complete GET |
| J12 LINK UID, LINK XML-REFERENCE, RELATED-TO UID | [RFC 9253 §§2, 7, 9.1](https://www.rfc-editor.org/rfc/rfc9253.html#section-2) | 500 / 500 for each |
| J15 PERIOD start/end and start/duration | [RFC 5545 §3.8.5.2](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.8.5.2) | 500 / 500 for each |

Run the probe against a disposable fixture account with a private file
containing its application password. For a private TLS fixture, also pass
`--ca-file /path/to/test-ca.pem`.

```sh
python3 qualification/clients/journal-admission.py \
  https://fixture.example/remote.php/dav/ fixture-user /private/app-password
```

The fixture process returns a failure for an incompatible provider even when
the prior state is intact. The tagged Nextcloud backend invokes
`getDateTimes()` for an RDATE attached to a dated component. Sabre's shipped
PERIOD property lacks that accessor, and the shipped VCalendar value map
does not include UID or XML-REFERENCE. Those are independent of date-range
REPORT behavior.
[Backend](https://github.com/nextcloud/server/blob/v35.0.3/apps/dav/lib/CalDAV/CalDavBackend.php)
[Period](https://github.com/nextcloud/3rdparty/blob/8d85b94d76506730b13deac503e12ed79234ee22/sabre/vobject/lib/Property/ICalendar/Period.php)
[VCalendar](https://github.com/nextcloud/3rdparty/blob/8d85b94d76506730b13deac503e12ed79234ee22/sabre/vobject/lib/Component/VCalendar.php).
