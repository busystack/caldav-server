-- Spent token verifiers remain available for replay detection for the family's lifetime.
-- These are SHA-256 digests of framework-generated high-entropy bearer tokens, never passwords.
CREATE TABLE oauth_refresh_families (
    authorization_id text PRIMARY KEY CHECK (length(authorization_id) BETWEEN 1 AND 200),
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    client_id text NOT NULL CHECK (length(client_id) BETWEEN 1 AND 200),
    recovery_epoch uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX oauth_refresh_families_account ON oauth_refresh_families(account_id);

CREATE TABLE oauth_refresh_tokens (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    authorization_id text NOT NULL REFERENCES oauth_refresh_families(authorization_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX oauth_refresh_tokens_family ON oauth_refresh_tokens(authorization_id);
