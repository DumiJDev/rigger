-- GitOps agent configuration, so it can be changed from the console instead of only via
-- environment variables (which required a restart to take effect). Mirrors
-- db/migration/sqlite/V5__gitops_config.sql.
--
-- Single-row table (id is always 'default'): Rigger tracks one GitOps repository, matching the
-- single-repository shape of the existing gitops_state table.
CREATE TABLE IF NOT EXISTS gitops_config (
    id                     TEXT PRIMARY KEY NOT NULL DEFAULT 'default',
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    repository_url         TEXT,
    branch                 TEXT NOT NULL DEFAULT 'main',
    ssh_key_path           TEXT,
    poll_interval_seconds  INTEGER NOT NULL DEFAULT 60,
    manifest_paths         TEXT NOT NULL DEFAULT 'manifests/',
    namespace_mapping      TEXT NOT NULL DEFAULT '{}',
    updated_at             TIMESTAMPTZ NOT NULL,
    updated_by             TEXT
);
