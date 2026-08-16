-- Adds HTTPS (username + token) as a second GitOps authentication mode, alongside the existing
-- SSH key path. Mirrors db/migration/sqlite/V7__gitops_https_auth.sql.
ALTER TABLE gitops_config ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'ssh';
ALTER TABLE gitops_config ADD COLUMN https_username TEXT;
ALTER TABLE gitops_config ADD COLUMN https_token_encrypted TEXT;
