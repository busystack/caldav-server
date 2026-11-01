-- A removed registration cannot leave live authorizations or token families behind.
-- Existing orphan state has no usable registered client and is deliberately invalidated.
DELETE FROM oauth2_authorization a
WHERE NOT EXISTS (SELECT 1 FROM oauth2_registered_client c WHERE c.id=a.registered_client_id);
DELETE FROM oauth2_authorization_consent a
WHERE NOT EXISTS (SELECT 1 FROM oauth2_registered_client c WHERE c.id=a.registered_client_id);
DELETE FROM oauth_refresh_families f
WHERE NOT EXISTS (SELECT 1 FROM oauth2_registered_client c WHERE c.client_id=f.client_id);

ALTER TABLE oauth2_registered_client
    ADD CONSTRAINT oauth2_registered_client_client_id_key
    UNIQUE USING INDEX oauth2_registered_client_client_id;
ALTER TABLE oauth2_authorization
    ADD CONSTRAINT oauth2_authorization_client_fk
    FOREIGN KEY (registered_client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE;
ALTER TABLE oauth2_authorization_consent
    ADD CONSTRAINT oauth2_authorization_consent_client_fk
    FOREIGN KEY (registered_client_id) REFERENCES oauth2_registered_client(id) ON DELETE CASCADE;
ALTER TABLE oauth_refresh_families
    ADD CONSTRAINT oauth_refresh_families_client_fk
    FOREIGN KEY (client_id) REFERENCES oauth2_registered_client(client_id) ON DELETE CASCADE;
