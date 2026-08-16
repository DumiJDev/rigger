-- Adds HTTPS (username + token) as a second GitOps authentication mode, alongside the existing
-- SSH key path. auth_type picks which of the two the agent uses; defaulting existing rows to
-- 'ssh' preserves current behaviour. The token is stored encrypted (AES-256-GCM via
-- SecretEncryptor), the same mechanism already used for Secret resource values — never plaintext.
ALTER TABLE gitops_config ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'ssh';
ALTER TABLE gitops_config ADD COLUMN https_username TEXT;
ALTER TABLE gitops_config ADD COLUMN https_token_encrypted TEXT;
