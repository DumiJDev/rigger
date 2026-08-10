-- GitOps agent configuration, so it can be changed from the console instead of only via
-- environment variables / application.yaml (which required a restart to take effect).
--
-- Single-row table (id is always 'default'): Rigger tracks one GitOps repository, matching the
-- single-repository shape of the existing gitops_state table.
--
-- Deliberately holds no credentials — only a path to an SSH key that already exists on the server.
-- Storing key material here would put it in the same database as everything else with no
-- encryption story; secrets go through SecretEncryptor or nowhere.
CREATE TABLE IF NOT EXISTS gitops_config (
    id                     TEXT PRIMARY KEY NOT NULL DEFAULT 'default',
    enabled                INTEGER NOT NULL DEFAULT 0,
    repository_url         TEXT,
    branch                 TEXT NOT NULL DEFAULT 'main',
    ssh_key_path           TEXT,
    poll_interval_seconds  INTEGER NOT NULL DEFAULT 60,
    manifest_paths         TEXT NOT NULL DEFAULT 'manifests/',
    namespace_mapping      TEXT NOT NULL DEFAULT '{}',
    updated_at             TEXT NOT NULL,
    updated_by             TEXT
);
