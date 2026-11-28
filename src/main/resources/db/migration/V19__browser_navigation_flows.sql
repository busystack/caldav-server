-- Short-lived browser flows share a stable scope through legitimate session rotation.
CREATE TABLE browser_flow_state (
    scope varchar(128) NOT NULL,
    section varchar(32) NOT NULL,
    payload text NOT NULL CHECK (octet_length(payload) <= 262144),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, section)
);
CREATE INDEX browser_flow_state_expiry ON browser_flow_state(expires_at);
