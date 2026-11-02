CREATE TABLE external_identities (
  issuer text COLLATE "C" NOT NULL CHECK (length(issuer) BETWEEN 1 AND 2048),
  subject text COLLATE "C" NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
  source text NOT NULL CHECK (source IN ('ALLOWLIST','LINKED')),
  last_verified_at timestamptz NOT NULL,
  linked_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (issuer,subject)
);
