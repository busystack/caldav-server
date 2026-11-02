-- Columns consumed by Spring Security 7.1.2 JdbcUserCredentialRepository;
-- extra foreign keys and ownership metadata keep passkeys tied to stable account IDs.
CREATE TABLE user_entities (
  id varchar(1000) PRIMARY KEY,
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
  name varchar(200) NOT NULL,
  display_name varchar(200)
);
CREATE TABLE user_credentials (
  credential_id varchar(1000) PRIMARY KEY,
  user_entity_user_id varchar(1000) NOT NULL REFERENCES user_entities(id) ON DELETE CASCADE,
  public_key bytea NOT NULL,
  signature_count bigint CHECK (signature_count >= 0),
  uv_initialized boolean,
  backup_eligible boolean NOT NULL,
  authenticator_transports varchar(1000),
  public_key_credential_type varchar(100),
  backup_state boolean NOT NULL,
  attestation_object bytea,
  attestation_client_data_json bytea,
  created timestamp,
  last_used timestamp,
  label varchar(1000) NOT NULL
);
CREATE INDEX user_credentials_user ON user_credentials(user_entity_user_id);
-- Tombstones prevent an assertion read before removal from reinserting a deleted key.
CREATE TABLE passkey_revocations (
  credential_id varchar(1000) PRIMARY KEY,
  revoked_at timestamptz NOT NULL DEFAULT now()
);
