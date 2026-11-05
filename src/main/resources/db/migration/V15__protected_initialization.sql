-- A singleton permanent latch. Existing installations, including disabled accounts,
-- are initialized. No account deletion or recovery can reopen this latch.
CREATE TABLE installation_state (
    id integer PRIMARY KEY CHECK (id = 1),
    initialized boolean NOT NULL,
    code_verifier bytea,
    expires_at timestamptz,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    initial_administrator uuid,
    welcome_completed_at timestamptz,
    CHECK ((code_verifier IS NULL) = (expires_at IS NULL)),
    CHECK (NOT initialized OR code_verifier IS NULL)
);
INSERT INTO installation_state(id, initialized)
VALUES (1, EXISTS(SELECT 1 FROM accounts));

-- Also cover established offline bootstrap and closed OIDC provisioning. Serialize
-- against browser claims and code issuance even when another process inserts.
CREATE FUNCTION close_initialization() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE installation_state SET initialized=true, code_verifier=NULL, expires_at=NULL
    WHERE id=1 AND NOT initialized;
    RETURN NEW;
END;
$$;
CREATE TRIGGER close_initialization BEFORE INSERT ON accounts
FOR EACH ROW EXECUTE FUNCTION close_initialization();
