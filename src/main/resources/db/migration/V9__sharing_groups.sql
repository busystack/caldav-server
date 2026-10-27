-- Account and group principal IDs share one durable namespace; retired IDs are never recycled.
CREATE TABLE dav_principals (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK(kind IN ('ACCOUNT','GROUP'))
);
INSERT INTO dav_principals(id,kind) SELECT id,'ACCOUNT' FROM accounts;
CREATE FUNCTION dav_account_principal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO dav_principals(id,kind) VALUES(NEW.id,'ACCOUNT');
    RETURN NEW;
END $$;
CREATE TRIGGER dav_account_principal BEFORE INSERT ON accounts FOR EACH ROW EXECUTE FUNCTION dav_account_principal();
ALTER TABLE accounts ADD CONSTRAINT accounts_principal_fk FOREIGN KEY(id) REFERENCES dav_principals(id);

CREATE TABLE sharing_groups (
    id uuid PRIMARY KEY REFERENCES dav_principals(id),
    name text NOT NULL UNIQUE CHECK(length(name) BETWEEN 1 AND 200),
    created_by uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION dav_group_principal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO dav_principals(id,kind) VALUES(NEW.id,'GROUP');
    RETURN NEW;
END $$;
CREATE TRIGGER dav_group_principal BEFORE INSERT ON sharing_groups FOR EACH ROW EXECUTE FUNCTION dav_group_principal();
CREATE TABLE sharing_group_members (
    group_id uuid NOT NULL REFERENCES sharing_groups(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    PRIMARY KEY(group_id,account_id)
);
CREATE INDEX sharing_group_members_account ON sharing_group_members(account_id,group_id);
CREATE TABLE collection_group_members (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    group_id uuid NOT NULL REFERENCES sharing_groups(id) ON DELETE CASCADE,
    permission text NOT NULL CHECK(permission IN ('READ','WRITE')),
    PRIMARY KEY(collection_id,group_id)
);
CREATE INDEX collection_group_members_group ON collection_group_members(group_id,collection_id);
CREATE VIEW collection_effective_members AS
SELECT collection_id,account_id,CASE WHEN bool_or(permission='WRITE') THEN 'WRITE' ELSE 'READ' END AS permission
FROM (
    SELECT collection_id,account_id,permission FROM collection_members
    UNION ALL
    SELECT a.collection_id,m.account_id,a.permission FROM collection_group_members a
        JOIN sharing_group_members m ON m.group_id=a.group_id
) grants GROUP BY collection_id,account_id;

DROP TRIGGER dav_member_binding ON collection_members;
DROP TRIGGER dav_member_unbinding ON collection_members;
DROP FUNCTION dav_member_binding();
CREATE FUNCTION dav_reconcile_memberships(target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE calendar_owner uuid; title text; recipient uuid; destination text;
BEGIN
    SELECT owner_id,name INTO calendar_owner,title FROM collections WHERE id=target FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
    SELECT e.account_id,
        CASE WHEN EXISTS(SELECT 1 FROM dav_bindings occupied WHERE occupied.home_id=e.account_id AND occupied.path=target::text)
             THEN target::text||'-'||gen_random_uuid()::text ELSE target::text END,
        target,title
    FROM collection_effective_members e
    WHERE e.collection_id=target AND e.account_id<>calendar_owner
      AND NOT EXISTS(SELECT 1 FROM dav_bindings b WHERE b.home_id=e.account_id AND b.collection_id=target);
    DELETE FROM dav_bindings b WHERE b.collection_id=target AND b.home_id<>calendar_owner
        AND NOT EXISTS(SELECT 1 FROM collection_effective_members e WHERE e.collection_id=target AND e.account_id=b.home_id);
    UPDATE collections SET generation=gen_random_uuid() WHERE id=target;
END $$;
CREATE FUNCTION dav_direct_membership_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('busystack.defer_dav_bindings',true)='on' THEN
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    PERFORM dav_reconcile_memberships(CASE WHEN TG_OP='DELETE' THEN OLD.collection_id ELSE NEW.collection_id END);
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER dav_direct_membership_changed AFTER INSERT OR UPDATE OR DELETE ON collection_members FOR EACH ROW EXECUTE FUNCTION dav_direct_membership_changed();
CREATE TRIGGER dav_group_acl_changed AFTER INSERT OR UPDATE OR DELETE ON collection_group_members FOR EACH ROW EXECUTE FUNCTION dav_direct_membership_changed();
CREATE FUNCTION dav_group_membership_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; affected_group uuid;
BEGIN
    IF current_setting('busystack.defer_dav_bindings',true)='on' THEN
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    affected_group:=CASE WHEN TG_OP='DELETE' THEN OLD.group_id ELSE NEW.group_id END;
    FOR target IN SELECT collection_id FROM collection_group_members WHERE group_id=affected_group ORDER BY collection_id LOOP
        PERFORM dav_reconcile_memberships(target);
    END LOOP;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER dav_group_membership_changed AFTER INSERT OR UPDATE OR DELETE ON sharing_group_members FOR EACH ROW EXECUTE FUNCTION dav_group_membership_changed();

-- Keep accepted XML language/attribute metadata until an explicit indexed-name replacement.
CREATE FUNCTION dav_collection_name_xml_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM dav_collection_properties WHERE collection_id=NEW.id AND namespace='DAV:' AND name='displayname';
    RETURN NEW;
END $$;
CREATE TRIGGER dav_collection_name_xml_changed AFTER UPDATE OF name ON collections FOR EACH ROW EXECUTE FUNCTION dav_collection_name_xml_changed();
CREATE FUNCTION dav_folder_name_xml_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM dav_binding_properties WHERE home_id=NEW.home_id AND path=NEW.path AND namespace='DAV:' AND name='displayname';
    RETURN NEW;
END $$;
CREATE TRIGGER dav_folder_name_xml_changed AFTER UPDATE OF display_name ON dav_bindings FOR EACH ROW WHEN(NEW.collection_id IS NULL) EXECUTE FUNCTION dav_folder_name_xml_changed();
