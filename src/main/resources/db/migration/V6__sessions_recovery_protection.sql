-- Standard Spring Session JDBC 4.1.1 PostgreSQL schema (Apache-2.0), managed by Flyway.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- Captured at credential lookup, so a login already validating an old credential cannot
-- create a valid session after disable/recovery changes this generation.
ALTER TABLE accounts ADD COLUMN security_epoch uuid NOT NULL DEFAULT gen_random_uuid();
-- Existing grants predate captured account generations and require fresh browser authorization.
UPDATE oauth_refresh_families SET revoked_at=COALESCE(revoked_at,now());

CREATE TABLE account_recovery_codes (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    recovery_epoch uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX account_recovery_codes_account ON account_recovery_codes(account_id);

CREATE TABLE webauthn_ceremonies (
    id uuid NOT NULL UNIQUE,
    session_id char(36) NOT NULL,
    purpose varchar(12) NOT NULL CHECK (purpose IN ('AUTHENTICATE','REGISTER')),
    account_id uuid REFERENCES accounts(id) ON DELETE CASCADE,
    security_epoch uuid,
    recovery_epoch uuid NOT NULL,
    challenge_hash char(64) NOT NULL CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, purpose),
    CHECK ((account_id IS NULL) = (security_epoch IS NULL)),
    CHECK (expires_at > created_at)
);
CREATE INDEX webauthn_ceremonies_expiry ON webauthn_ceremonies(expires_at);

CREATE TABLE login_throttle (
    bucket_key char(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    window_started timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts BETWEEN 0 AND 1000),
    blocked_until timestamptz,
    expires_at timestamptz NOT NULL
);
CREATE INDEX login_throttle_expiry ON login_throttle(expires_at);

CREATE TABLE security_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    account_id uuid REFERENCES accounts(id) ON DELETE SET NULL,
    action varchar(40) NOT NULL,
    outcome varchar(12) NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE','THROTTLED')),
    peer_hash char(64) CHECK (peer_hash IS NULL OR peer_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX security_audit_time ON security_audit(occurred_at);
