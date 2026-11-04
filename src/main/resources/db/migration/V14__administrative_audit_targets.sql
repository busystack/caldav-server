-- Opaque administrative targets survive deletion of the target resource. Historical rows
-- intentionally remain unclassified. No names, calendar metadata, secrets or free text.
ALTER TABLE security_audit
    ADD COLUMN target_type varchar(16),
    ADD COLUMN target_id uuid,
    ADD COLUMN related_account_id uuid,
    ADD CONSTRAINT security_audit_target CHECK (
        (target_type IS NULL AND target_id IS NULL AND related_account_id IS NULL)
        OR (target_type IS NOT NULL AND target_type IN ('ACCOUNT','GROUP') AND target_id IS NOT NULL
            AND (related_account_id IS NULL OR target_type='GROUP'))
    );
