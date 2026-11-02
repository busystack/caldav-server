-- Preserve existing values. Oversized legacy targets may be reduced or removed; no new
-- target may grow beyond 1 MiB / 1,000 values. A no-op parent UPDATE serializes
-- READ COMMITTED writers and forces stale REPEATABLE READ writers to abort (40001).
-- These columns deliberately do not fire indexed display-name/property-removal triggers.
CREATE FUNCTION dav_property_storage_budget() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous_bytes bigint;
    previous_count bigint;
    resulting_bytes bigint;
    resulting_count bigint;
BEGIN
    IF TG_TABLE_NAME='dav_binding_properties' THEN
        UPDATE dav_bindings SET home_id=home_id WHERE home_id=NEW.home_id AND path=NEW.path;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO previous_bytes,previous_count
            FROM dav_binding_properties WHERE home_id=NEW.home_id AND path=NEW.path;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO resulting_bytes,resulting_count
            FROM dav_binding_properties WHERE home_id=NEW.home_id AND path=NEW.path
            AND NOT(namespace=NEW.namespace AND name=NEW.name);
    ELSIF TG_TABLE_NAME='dav_collection_properties' THEN
        UPDATE collections SET revision=revision WHERE id=NEW.collection_id;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO previous_bytes,previous_count
            FROM dav_collection_properties WHERE collection_id=NEW.collection_id;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO resulting_bytes,resulting_count
            FROM dav_collection_properties WHERE collection_id=NEW.collection_id
            AND NOT(namespace=NEW.namespace AND name=NEW.name);
    ELSE
        UPDATE collections SET revision=revision WHERE id=NEW.collection_id;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO previous_bytes,previous_count
            FROM dav_resource_properties WHERE collection_id=NEW.collection_id AND resource_name=NEW.resource_name;
        SELECT COALESCE(sum(octet_length(xml)),0),count(*) INTO resulting_bytes,resulting_count
            FROM dav_resource_properties WHERE collection_id=NEW.collection_id AND resource_name=NEW.resource_name
            AND NOT(namespace=NEW.namespace AND name=NEW.name);
    END IF;
    resulting_bytes:=resulting_bytes+octet_length(NEW.xml);
    resulting_count:=resulting_count+1;
    IF (resulting_bytes>1048576 AND resulting_bytes>previous_bytes)
        OR (resulting_count>1000 AND resulting_count>previous_count) THEN
        RAISE EXCEPTION 'property_storage_budget exceeded' USING ERRCODE='23514',CONSTRAINT='property_storage_budget';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER dav_binding_property_budget BEFORE INSERT OR UPDATE OF xml ON dav_binding_properties
FOR EACH ROW EXECUTE FUNCTION dav_property_storage_budget();
CREATE TRIGGER dav_collection_property_budget BEFORE INSERT OR UPDATE OF xml ON dav_collection_properties
FOR EACH ROW EXECUTE FUNCTION dav_property_storage_budget();
CREATE TRIGGER dav_resource_property_budget BEFORE INSERT OR UPDATE OF xml ON dav_resource_properties
FOR EACH ROW EXECUTE FUNCTION dav_property_storage_budget();
