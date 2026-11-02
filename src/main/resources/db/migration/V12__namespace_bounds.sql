-- These bounds mirror DavNamespace. Deployment prefixes are validated separately.
CREATE FUNCTION dav_uri_encoded_length(value text) RETURNS integer
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE data bytea:=convert_to(value,'UTF8'); total integer:=0; code integer; i integer;
BEGIN
    FOR i IN 0..octet_length(data)-1 LOOP
        code:=get_byte(data,i);
        total:=total+CASE WHEN code BETWEEN 65 AND 90 OR code BETWEEN 97 AND 122
            OR code BETWEEN 48 AND 57 OR code IN (45,46,95,126) THEN 1 ELSE 3 END;
    END LOOP;
    RETURN total;
END $$;

CREATE FUNCTION dav_namespace_valid(path text, calendar boolean, leaf text DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE segments text[]:=string_to_array(path,'/'); part text; units integer; cp integer;
    total integer:=46; count integer:=2; i integer;
BEGIN
    IF path IS NULL OR calendar IS NULL THEN RETURN false; END IF;
    -- A single ASCII unreserved segment has exactly one encoded byte / UTF-16 unit.
    -- At most four fixed/path/leaf segments and 2,598 encoded bytes (including the
    -- worst permitted future calendar leaf) are possible here. Preserve all limits
    -- while avoiding character-by-character work for ordinary UUID share bindings.
    IF length(path)<=255 AND path COLLATE "C" ~ '^[A-Za-z0-9._~-]+$' AND path NOT IN ('.','..')
       AND (leaf IS NULL OR (length(leaf)<=255 AND leaf COLLATE "C" ~ '^[A-Za-z0-9._~-]+$' AND leaf NOT IN ('.','..'))) THEN
        RETURN true;
    END IF;
    IF leaf IS NOT NULL THEN segments:=array_append(segments,leaf); END IF;
    FOREACH part IN ARRAY segments LOOP
        IF part='' OR part IN ('.','..') OR position('/' IN part)>0 OR position(';' IN part)>0 OR part ~ '[\\[:cntrl:]]'
            OR translate(part,U&'\0020\1680\2000\2001\2002\2003\2004\2005\2006\2008\2009\200A\2028\2029\205F\3000','')=''
            THEN RETURN false; END IF;
        units:=0;
        FOR i IN 1..length(part) LOOP
            cp:=ascii(substring(part FROM i FOR 1));
            IF cp BETWEEN 0 AND 31 OR cp BETWEEN 127 AND 159 OR cp IN (8232,8233,65534,65535) THEN RETURN false; END IF;
            units:=units+CASE WHEN cp>65535 THEN 2 ELSE 1 END;
        END LOOP;
        IF units>255 THEN RETURN false; END IF;
        count:=count+1; total:=total+dav_uri_encoded_length(part)+1;
    END LOOP;
    IF leaf IS NULL AND calendar THEN
        -- Reserve a complete ordinary resource leaf, including the worst permitted encoding.
        count:=count+1; total:=total+2296;
    END IF;
    RETURN count<=20 AND total<=4096;
END $$;

-- STRICT cannot be used with the optional NULL leaf argument.
ALTER FUNCTION dav_namespace_valid(text,boolean,text) CALLED ON NULL INPUT;

CREATE FUNCTION dav_binding_namespace_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT dav_namespace_valid(NEW.path,NEW.collection_id IS NOT NULL,NULL) THEN
        RAISE EXCEPTION 'DAV namespace bound exceeded' USING ERRCODE='23514';
    END IF;
    IF NEW.collection_id IS NOT NULL AND EXISTS(
        SELECT 1 FROM calendar_resources r WHERE r.collection_id=NEW.collection_id
            AND NOT dav_namespace_valid(NEW.path,false,r.name)) THEN
        RAISE EXCEPTION 'DAV descendant namespace bound exceeded' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dav_binding_namespace_guard BEFORE INSERT OR UPDATE OF path,collection_id ON dav_bindings
FOR EACH ROW EXECUTE FUNCTION dav_binding_namespace_guard();

CREATE FUNCTION dav_resource_namespace_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT dav_namespace_valid(NEW.collection_id::text,false,NEW.name) THEN
        RAISE EXCEPTION 'DAV resource segment bound exceeded' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM dav_bindings b WHERE b.collection_id=NEW.collection_id
        AND NOT dav_namespace_valid(b.path,false,NEW.name)) THEN
        RAISE EXCEPTION 'DAV resource namespace bound exceeded' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dav_resource_namespace_guard BEFORE INSERT OR UPDATE OF collection_id,name ON calendar_resources
FOR EACH ROW EXECUTE FUNCTION dav_resource_namespace_guard();
