-- Immutable representations let separate HTTP pages share a revision watermark without
-- holding a PostgreSQL transaction open between requests. Revisions remain collection-locked.
CREATE TABLE calendar_resource_versions (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    name text COLLATE "C" NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    deleted boolean NOT NULL,
    uid text,
    content bytea,
    content_length integer GENERATED ALWAYS AS (octet_length(content)) STORED,
    etag text,
    updated_at timestamptz,
    PRIMARY KEY (collection_id,name,revision),
    CHECK ((deleted AND uid IS NULL AND content IS NULL AND etag IS NULL AND updated_at IS NULL)
        OR (NOT deleted AND uid IS NOT NULL AND content IS NOT NULL AND etag IS NOT NULL AND updated_at IS NOT NULL))
);
CREATE INDEX calendar_resource_versions_revision ON calendar_resource_versions(collection_id,revision);
-- Mixed ordering matches DISTINCT ON/keyset traversal and lets PostgreSQL stop at the page
-- boundary instead of sorting the remaining collection. Bodies stay out of this index.
CREATE INDEX calendar_resource_versions_page ON calendar_resource_versions(collection_id,name,revision DESC) INCLUDE(deleted,etag,updated_at,content_length);

-- Keep historical property values in separate indexed rows. Propname never reads XML,
-- and selecting one property never detoasts the other properties of the resource version.
CREATE TABLE calendar_resource_version_properties (
    collection_id uuid NOT NULL,
    resource_name text COLLATE "C" NOT NULL,
    revision bigint NOT NULL,
    namespace text NOT NULL CHECK(length(namespace)<=1024),
    property_name text NOT NULL CHECK(length(property_name) BETWEEN 1 AND 200),
    xml text NOT NULL CHECK(octet_length(xml)<=65536),
    PRIMARY KEY(collection_id,resource_name,revision,namespace,property_name),
    FOREIGN KEY(collection_id,resource_name,revision)
        REFERENCES calendar_resource_versions(collection_id,name,revision) ON DELETE CASCADE
);

INSERT INTO calendar_resource_versions(collection_id,name,revision,deleted,uid,content,etag,updated_at)
SELECT collection_id,name,revision,false,uid,content,etag,updated_at FROM calendar_resources;
INSERT INTO calendar_resource_version_properties(collection_id,resource_name,revision,namespace,property_name,xml)
SELECT r.collection_id,r.name,r.revision,p.namespace,p.name,p.xml FROM calendar_resources r
JOIN dav_resource_properties p ON p.collection_id=r.collection_id AND p.resource_name=r.name;
-- Pre-upgrade stable tokens remain usable; old deletions do not have current rows to backfill.
INSERT INTO calendar_resource_versions(collection_id,name,revision,deleted)
SELECT collection_id,name,revision,true FROM collection_changes WHERE deleted
ON CONFLICT DO NOTHING;

CREATE FUNCTION capture_calendar_resource_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR OLD.revision IS DISTINCT FROM NEW.revision
       OR OLD.collection_id IS DISTINCT FROM NEW.collection_id OR OLD.name IS DISTINCT FROM NEW.name THEN
        INSERT INTO calendar_resource_versions(collection_id,name,revision,deleted,uid,content,etag,updated_at)
        VALUES(NEW.collection_id,NEW.name,NEW.revision,false,NEW.uid,NEW.content,NEW.etag,NEW.updated_at);
        INSERT INTO calendar_resource_version_properties(collection_id,resource_name,revision,namespace,property_name,xml)
        SELECT NEW.collection_id,NEW.name,NEW.revision,p.namespace,p.name,p.xml
        FROM dav_resource_properties p WHERE p.collection_id=NEW.collection_id AND p.resource_name=NEW.name;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER calendar_resource_version_write AFTER INSERT OR UPDATE ON calendar_resources
FOR EACH ROW EXECUTE FUNCTION capture_calendar_resource_version();

CREATE FUNCTION capture_calendar_resource_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.deleted THEN
        INSERT INTO calendar_resource_versions(collection_id,name,revision,deleted)
        VALUES(NEW.collection_id,NEW.name,NEW.revision,true);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER calendar_resource_version_delete AFTER INSERT ON collection_changes
FOR EACH ROW EXECUTE FUNCTION capture_calendar_resource_tombstone();

-- COPY writes properties after inserting the resource. At commit, fill only an empty final
-- current version; a later PROPPATCH must never overwrite a previous version's properties.
CREATE FUNCTION capture_calendar_resource_version_properties() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_collection uuid; target_name text;
BEGIN
    IF TG_OP='DELETE' THEN target_collection:=OLD.collection_id; target_name:=OLD.resource_name;
    ELSE target_collection:=NEW.collection_id; target_name:=NEW.resource_name; END IF;
    INSERT INTO calendar_resource_version_properties(collection_id,resource_name,revision,namespace,property_name,xml)
    SELECT r.collection_id,r.name,r.revision,p.namespace,p.name,p.xml FROM calendar_resources r
    JOIN dav_resource_properties p ON p.collection_id=r.collection_id AND p.resource_name=r.name
    WHERE r.collection_id=target_collection AND r.name=target_name
      AND NOT EXISTS(SELECT 1 FROM calendar_resource_version_properties v
                     WHERE v.collection_id=r.collection_id AND v.resource_name=r.name AND v.revision=r.revision)
    ON CONFLICT DO NOTHING;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER calendar_resource_version_property_write AFTER INSERT OR UPDATE OR DELETE ON dav_resource_properties
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION capture_calendar_resource_version_properties();

CREATE TABLE sync_continuations (
    id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    generation uuid NOT NULL,
    recovery_epoch uuid NOT NULL,
    lower_revision bigint NOT NULL CHECK(lower_revision>=0),
    upper_revision bigint NOT NULL CHECK(upper_revision>=lower_revision),
    retention_floor bigint NOT NULL CHECK(retention_floor>=0),
    initial_sync boolean NOT NULL,
    last_name text COLLATE "C" NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT (now()+interval '24 hours')
);
CREATE INDEX sync_continuations_expiry ON sync_continuations(expires_at);
