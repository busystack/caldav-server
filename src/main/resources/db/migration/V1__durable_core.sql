CREATE TABLE system_state (
    id integer PRIMARY KEY CHECK (id = 1),
    recovery_epoch uuid NOT NULL DEFAULT gen_random_uuid()
);
INSERT INTO system_state(id) VALUES (1);

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE CHECK (username ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$'),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    password_hash text,
    administrator boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dav_credentials (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    label text NOT NULL CHECK (length(label) BETWEEN 1 AND 100),
    verifier text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX dav_credentials_account ON dav_credentials(account_id);

CREATE TABLE collections (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES accounts(id),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    component text NOT NULL CHECK (component IN ('VEVENT', 'VTODO')),
    generation uuid NOT NULL DEFAULT gen_random_uuid(),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    min_retained_revision bigint NOT NULL DEFAULT 0 CHECK (min_retained_revision >= 0 AND min_retained_revision <= revision),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX collections_owner ON collections(owner_id);

CREATE TABLE collection_members (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    permission text NOT NULL CHECK (permission IN ('READ', 'WRITE')),
    PRIMARY KEY (collection_id, account_id)
);
CREATE INDEX collection_members_account ON collection_members(account_id);

CREATE TABLE calendar_resources (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 255 AND name NOT IN ('.', '..') AND name !~ '[/\\[:cntrl:]]'),
    uid text NOT NULL CHECK (length(uid) BETWEEN 1 AND 1024),
    content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 1048576),
    etag text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, name),
    UNIQUE (collection_id, uid)
);

-- The collection row lock serializes revision allocation and remains held through commit.
-- A sequence would allow a late-committing mutation to disappear behind a delivered token.
CREATE TABLE collection_changes (
    collection_id uuid NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0),
    name text NOT NULL,
    deleted boolean NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, revision)
);
CREATE INDEX collection_changes_resource ON collection_changes(collection_id, name, revision);
