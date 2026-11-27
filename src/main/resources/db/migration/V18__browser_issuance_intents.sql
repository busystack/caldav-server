ALTER TABLE accounts ADD COLUMN recovery_code_generation uuid;

CREATE TABLE browser_issuance_intents (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    security_epoch uuid NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('APPLICATION_PASSWORD', 'RECOVERY_CODES')),
    context varchar(64) NOT NULL,
    expected_generation uuid,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    result_id uuid,
    CHECK (result_id IS NULL OR consumed_at IS NOT NULL)
);

CREATE INDEX browser_issuance_intents_expiry ON browser_issuance_intents (expires_at);
CREATE INDEX browser_issuance_intents_account_pending
    ON browser_issuance_intents (account_id) WHERE consumed_at IS NULL;
