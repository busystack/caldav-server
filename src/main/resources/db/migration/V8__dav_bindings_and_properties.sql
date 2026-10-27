CREATE TABLE dav_bindings (
    home_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    path text NOT NULL CHECK (length(path) BETWEEN 1 AND 4096 AND path NOT LIKE '/%' AND path NOT LIKE '%/' AND path NOT LIKE '%//%' AND path !~ '[\\[:cntrl:]]'),
    collection_id uuid REFERENCES collections(id) ON DELETE CASCADE,
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    PRIMARY KEY (home_id,path)
);
CREATE UNIQUE INDEX dav_bindings_one_collection_per_home ON dav_bindings(home_id,collection_id)
    WHERE collection_id IS NOT NULL;

INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
SELECT owner_id,id::text,id,name FROM collections;
INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
SELECT m.account_id,c.id::text,c.id,c.name FROM collection_members m JOIN collections c ON c.id=m.collection_id
ON CONFLICT DO NOTHING;

CREATE FUNCTION dav_collection_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
        VALUES (NEW.owner_id,NEW.id::text,NEW.id,NEW.name);
    ELSE
        UPDATE dav_bindings SET display_name=NEW.name WHERE collection_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dav_collection_binding AFTER INSERT OR UPDATE OF name ON collections
FOR EACH ROW EXECUTE FUNCTION dav_collection_binding();

CREATE FUNCTION dav_member_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE title text; destination text;
BEGIN
    IF TG_OP='DELETE' THEN
        DELETE FROM dav_bindings WHERE home_id=OLD.account_id AND collection_id=OLD.collection_id;
        RETURN OLD;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM dav_bindings WHERE home_id=NEW.account_id AND collection_id=NEW.collection_id) THEN
        SELECT name INTO title FROM collections WHERE id=NEW.collection_id;
        destination:=NEW.collection_id::text;
        IF EXISTS(SELECT 1 FROM dav_bindings WHERE home_id=NEW.account_id AND path=destination) THEN
            destination:=NEW.collection_id::text||'-'||gen_random_uuid()::text;
        END IF;
        INSERT INTO dav_bindings(home_id,path,collection_id,display_name)
        VALUES (NEW.account_id,destination,NEW.collection_id,title);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dav_member_binding AFTER INSERT OR UPDATE ON collection_members
FOR EACH ROW EXECUTE FUNCTION dav_member_binding();
CREATE TRIGGER dav_member_unbinding AFTER DELETE ON collection_members
FOR EACH ROW EXECUTE FUNCTION dav_member_binding();

CREATE TABLE dav_binding_properties (
    home_id uuid NOT NULL,
    path text NOT NULL,
    namespace text NOT NULL CHECK (length(namespace)<=1024),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    xml text NOT NULL CHECK (octet_length(xml)<=65536),
    PRIMARY KEY(home_id,path,namespace,name),
    FOREIGN KEY(home_id,path) REFERENCES dav_bindings(home_id,path) ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE TABLE dav_collection_properties (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    namespace text NOT NULL CHECK (length(namespace)<=1024),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    xml text NOT NULL CHECK (octet_length(xml)<=65536),
    PRIMARY KEY(collection_id,namespace,name)
);
CREATE TABLE dav_resource_properties (
    collection_id uuid NOT NULL,
    resource_name text NOT NULL,
    namespace text NOT NULL CHECK (length(namespace)<=1024),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    xml text NOT NULL CHECK (octet_length(xml)<=65536),
    PRIMARY KEY(collection_id,resource_name,namespace,name),
    FOREIGN KEY(collection_id,resource_name) REFERENCES calendar_resources(collection_id,name) ON DELETE CASCADE ON UPDATE CASCADE
);
