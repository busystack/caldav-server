"""Small original-audit corpus across the actual packaged HTTPS backup/restore workflow."""

import hashlib
import json
import xml.etree.ElementTree as XML
from pathlib import Path


class Corpus:
    VALID = (
        "control-event.ics", "control-last-ordinary-second.ics", "future-zone.ics",
        "valid-lowercase-delimiters.ics", "valid-rfc9073-location.ics",
        "valid-rfc9074-alarm.ics", "valid-rfc9253-relationship.ics",
        "valid-text-list.ics", "valid-unknown-x-properties.ics",
    )

    def __init__(self, checkpoint):
        self.checkpoint = checkpoint
        self.fixture = checkpoint.root / "src/test/resources/calendar/audit"
        self.resources, self.collections, self.tokens = {}, {}, {}

    def call(self, encoded, path, method="GET", body=None, **headers):
        return self.checkpoint.request(path, method,
            {"Authorization": "Basic " + encoded, **headers}, body)

    def seed(self, environment):
        point = self.checkpoint
        encoded = point.saved["encoded"]
        for component, suffix in (("VEVENT", "events"), ("VTODO", "tasks")):
            name = "independent-audit-restore-" + suffix
            path = "/dav/calendars/" + point.owner + "/" + name + "/"
            body = ('<C:mkcalendar xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">'
                '<D:set><D:prop><C:supported-calendar-component-set><C:comp name="' + component + '"/>'
                '</C:supported-calendar-component-set></D:prop></D:set></C:mkcalendar>').encode()
            assert self.call(encoded, path, "MKCALENDAR", body, **{"Content-Type": "application/xml"})[0] == 201
            identifier = point.operations.sql(environment,
                "SELECT collection_id FROM dav_bindings WHERE home_id='" + point.owner + "' AND path='" + name + "';")
            assert identifier
            self.collections[component] = {"path": path, "id": identifier}
        for name in self.VALID:
            content = (self.fixture / name).read_bytes()
            component = "VTODO" if b"BEGIN:VTODO" in content else "VEVENT"
            path = self.collections[component]["path"] + name
            assert self.call(encoded, path, "PUT", content,
                **{"Content-Type": "text/calendar", "If-None-Match": "*"})[0] == 201, name
            status, headers, raw = self.call(encoded, path)
            assert status == 200 and raw == content, name
            self.resources[path] = {"content": content, "etag": headers["ETag"], "component": component}
        # The invalid originals are rejection controls, never silently introduced into this dump.
        for fixture in sorted(self.fixture.glob("invalid-*.ics")):
            content = fixture.read_bytes()
            component = "VTODO" if b"BEGIN:VTODO" in content else "VEVENT"
            collection = self.collections[component]
            before = point.report(encoded, path=collection["path"])[2]
            old = XML.fromstring(before).findtext("{DAV:}sync-token")
            path = collection["path"] + fixture.name
            status, _, body = self.call(encoded, path, "PUT", content, **{"Content-Type": "text/calendar"})
            assert status == 403 and XML.fromstring(body).find("{urn:ietf:params:xml:ns:caldav}valid-calendar-data") is not None
            assert self.call(encoded, path)[0] == 404
            after = point.report(encoded, path=collection["path"])[2]
            assert XML.fromstring(after).findtext("{DAV:}sync-token") == old
        # Keep one immutable deletion history entry in the actual archive.
        self.deleted_path = self.collections["VTODO"]["path"] + "deleted-history.ics"
        assert self.call(encoded, self.deleted_path, "PUT", point.content,
            **{"Content-Type": "text/calendar"})[0] == 201
        assert self.call(encoded, self.deleted_path, "DELETE")[0] == 204
        assert self.call(encoded, self.deleted_path)[0] == 404
        # Explicit resource properties add real history and historical property snapshots.
        properties = (b'<D:propertyupdate xmlns:D="DAV:" xmlns:A="urn:busystack:audit-restore" xml:lang="en">'
            b'<D:set><D:prop><A:proof>before <A:b xml:lang="fr">apres</A:b> after</A:proof></D:prop></D:set>'
            b'</D:propertyupdate>')
        self.property_path = self.collections["VEVENT"]["path"] + "future-zone.ics"
        assert self.call(encoded, self.property_path, "PROPPATCH", properties,
            **{"Content-Type": "application/xml"})[0] == 207
        for collection in self.collections.values():
            assert self.call(encoded, collection["path"], "PROPPATCH", properties,
                **{"Content-Type": "application/xml"})[0] == 207
            self.grant(encoded, collection["path"], point.reader, point.writer)
            status, _, body = point.report(encoded, path=collection["path"])
            assert status == 207
            self.tokens[collection["path"]] = XML.fromstring(body).findtext("{DAV:}sync-token")
        # Leave a real immutable partial cursor in the archive as well as final stable tokens.
        status, _, body = point.report(encoded, path=self.collections["VEVENT"]["path"], limit=1)
        assert status == 207 and b"507 Insufficient Storage" in body
        self.cursor = XML.fromstring(body).findtext("{DAV:}sync-token")
        self.verify_owner(encoded)
        self.database_snapshot = self.database_state(environment)
        print("HTTPS_AUDIT_CORPUS_CREATED=" + json.dumps({
            "acceptedOriginals": len(self.resources), "rejectedOriginals": 9,
            "contentSha256": {Path(path).name: hashlib.sha256(value["content"]).hexdigest()
                for path, value in self.resources.items()},
            "historyAndPropertySnapshots": True, "deletedResourceHistory": True,
            "collectionPropertiesAndDirectReadWriteGrants": True, "durablePartialCursor": True,
        }, sort_keys=True), flush=True)

    def grant(self, encoded, path, *principals):
        acl = XML.Element("{DAV:}acl")
        for index, principal in enumerate(principals):
            ace = XML.SubElement(acl, "{DAV:}ace")
            XML.SubElement(XML.SubElement(ace, "{DAV:}principal"), "{DAV:}href").text = (
                self.checkpoint.base + "/dav/principals/" + principal + "/")
            grant = XML.SubElement(ace, "{DAV:}grant")
            XML.SubElement(XML.SubElement(grant, "{DAV:}privilege"), "{DAV:}read")
            if index:
                XML.SubElement(XML.SubElement(grant, "{DAV:}privilege"), "{DAV:}write")
        assert self.call(encoded, path, "ACL", XML.tostring(acl), **{"Content-Type": "application/xml"})[0] == 200

    def database_state(self, environment):
        ids = ",".join("'" + value["id"] + "'" for value in self.collections.values())
        query = ("SELECT json_build_object('versions',(SELECT json_agg(row_to_json(v) ORDER BY collection_id,name,revision) "
            "FROM (SELECT collection_id,name,revision,deleted,encode(sha256(content),'hex') AS sha256,etag,updated_at "
            "FROM calendar_resource_versions WHERE collection_id IN (" + ids + ")) v),"
            "'properties',(SELECT json_agg(row_to_json(p) ORDER BY collection_id,resource_name,revision,namespace,property_name) "
            "FROM calendar_resource_version_properties p WHERE collection_id IN (" + ids + ")),"
            "'collectionProperties',(SELECT json_agg(row_to_json(p) ORDER BY collection_id,namespace,name) "
            "FROM dav_collection_properties p WHERE collection_id IN (" + ids + ")),"
            "'directGrants',(SELECT json_agg(row_to_json(m) ORDER BY collection_id,account_id) "
            "FROM collection_members m WHERE collection_id IN (" + ids + ")));")
        return json.loads(self.checkpoint.operations.sql(environment, query))

    def assert_database(self, environment):
        assert self.database_state(environment) == self.database_snapshot
        print("HTTPS_AUDIT_CORPUS_RESTORED_IMMUTABLE_HISTORY_AND_PROPERTIES_PASSED", flush=True)

    def verify_owner(self, encoded):
        assert self.call(encoded, self.deleted_path)[0] == 404
        for path, value in self.resources.items():
            status, headers, content = self.call(encoded, path)
            assert status == 200 and content == value["content"] and headers["ETag"] == value["etag"], path
        path = self.collections["VEVENT"]["path"]
        for name, expected in (("future-query-correct.xml", True), ("future-query-incorrect.xml", False)):
            status, _, body = self.call(encoded, path, "REPORT", (self.fixture / name).read_bytes(),
                **{"Depth": "1", "Content-Type": "application/xml"})
            assert status == 207
            found = any((node.text or "").endswith("/future-zone.ics") for node in XML.fromstring(body).findall(".//{DAV:}href"))
            assert found == expected, name
        free = (b'<C:free-busy-query xmlns:C="urn:ietf:params:xml:ns:caldav">'
            b'<C:time-range start="20400401T090000Z" end="20400401T130000Z"/></C:free-busy-query>')
        status, _, busy = self.call(encoded, path, "REPORT", free, **{"Depth": "1", "Content-Type": "application/xml"})
        assert status == 200 and b"20400401T100000Z/20400401T110000Z" in busy
        assert busy.count(b"DTSTAMP:") == busy.count(b"UID:") == busy.count(b"BEGIN:VFREEBUSY") == 1
        root = XML.Element("{urn:ietf:params:xml:ns:caldav}calendar-multiget")
        prop = XML.SubElement(root, "{DAV:}prop")
        XML.SubElement(XML.SubElement(prop, "{urn:ietf:params:xml:ns:caldav}calendar-data"),
            "{urn:ietf:params:xml:ns:caldav}expand", start="20400401T090000Z", end="20400401T130000Z")
        XML.SubElement(root, "{DAV:}href").text = self.checkpoint.base + self.property_path
        status, _, data = self.call(encoded, path, "REPORT", XML.tostring(root), **{"Content-Type": "application/xml"})
        assert status == 207
        expanded = XML.fromstring(data).findtext(".//{urn:ietf:params:xml:ns:caldav}calendar-data")
        assert "DTSTART:20400401T100000Z" in expanded and "DTEND:20400401T110000Z" in expanded
        status, _, body = self.call(encoded, self.property_path, "PROPFIND",
            b'<D:propfind xmlns:D="DAV:" xmlns:A="urn:busystack:audit-restore"><D:prop><A:proof/></D:prop></D:propfind>',
            **{"Content-Type": "application/xml", "Depth": "0"})
        assert status == 207
        proof = XML.fromstring(body).find(".//{urn:busystack:audit-restore}proof")
        assert proof.text == "before " and proof[0].text == "apres" and proof[0].tail == " after"
        assert proof.attrib["{http://www.w3.org/XML/1998/namespace}lang"] == "en"
        for resource, value in self.resources.items():
            assert self.call(encoded, resource)[2] == value["content"]

    def verify_restored(self, encoded, reader_encoded):
        self.verify_owner(encoded)
        for component, collection in self.collections.items():
            for token in [self.tokens[collection["path"]]] + ([self.cursor] if component == "VEVENT" else []):
                status, _, body = self.checkpoint.report(encoded, token, path=collection["path"])
                assert status == 403 and XML.fromstring(body).find("{DAV:}valid-sync-token") is not None
            assert self.checkpoint.report(encoded, path=collection["path"])[0] == 207
        for path, value in self.resources.items():
            collection = self.collections[value["component"]]
            reader_path = "/dav/calendars/" + self.checkpoint.reader + "/" + collection["id"] + "/" + Path(path).name
            assert self.call(reader_encoded, reader_path)[2] == value["content"]
            assert self.call(reader_encoded, reader_path, "PUT", value["content"],
                **{"Content-Type": "text/calendar", "If-Match": value["etag"]})[0] == 403
        print("HTTPS_AUDIT_CORPUS_BYTE_EXACT_TIMEZONE_REPORTS_READ_SHARES_AND_OLD_TOKENS_PASSED", flush=True)

    def revoke(self, encoded, reader_encoded):
        for collection in self.collections.values():
            self.grant(encoded, collection["path"])
        for path, value in self.resources.items():
            collection = self.collections[value["component"]]
            reader_path = "/dav/calendars/" + self.checkpoint.reader + "/" + collection["id"] + "/" + Path(path).name
            assert self.call(reader_encoded, reader_path)[0] in (403, 404)
            assert self.call(encoded, path)[2] == value["content"]
        print("HTTPS_AUDIT_CORPUS_REVOKED_SHARES_DENIED_OWNER_BYTES_PRESERVED", flush=True)
