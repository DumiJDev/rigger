-- Rigger initial schema (PostgreSQL).
-- All tables use TEXT primary keys (ULIDs) for time-sortability.
-- Native types are used where SQLite had to fake them: BOOLEAN instead of an INTEGER 0/1 flag,
-- TIMESTAMPTZ instead of a formatted TEXT string. Structure otherwise mirrors
-- db/migration/sqlite/V1__initial_schema.sql exactly — keep the two in lockstep.

CREATE TABLE IF NOT EXISTS cluster_nodes (
    name          TEXT PRIMARY KEY NOT NULL,
    ip            TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('MANAGER','WORKER')),
    is_primary    BOOLEAN NOT NULL DEFAULT FALSE,
    status        TEXT NOT NULL CHECK (status IN ('PENDING','PROVISIONING','ACTIVE','DRAINING','OFFLINE')),
    cluster_name  TEXT NOT NULL,
    labels_json   TEXT,
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    swarm_node_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_nodes_cluster ON cluster_nodes(cluster_name);
CREATE INDEX IF NOT EXISTS idx_nodes_status  ON cluster_nodes(status);

-- Generic resource table (Deployment, Service, ConfigMap, Secret, Pod, etc.)
CREATE TABLE IF NOT EXISTS resources (
    id           TEXT PRIMARY KEY NOT NULL,
    kind         TEXT NOT NULL,
    namespace    TEXT NOT NULL,
    name         TEXT NOT NULL,
    spec_json    TEXT NOT NULL,
    labels_json  TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    applied_by   TEXT,
    UNIQUE (kind, namespace, name)
);

CREATE INDEX IF NOT EXISTS idx_resources_ns_kind ON resources(namespace, kind);
CREATE INDEX IF NOT EXISTS idx_resources_kind     ON resources(kind);

-- Audit log: append-only, no DELETE or UPDATE allowed from application code
CREATE TABLE IF NOT EXISTS audit_log (
    id             TEXT PRIMARY KEY NOT NULL,
    identity_name  TEXT NOT NULL,
    identity_role  TEXT NOT NULL,
    action         TEXT NOT NULL,
    resource_kind  TEXT,
    resource_name  TEXT,
    namespace      TEXT,
    source_ip      TEXT NOT NULL,
    timestamp      TIMESTAMPTZ NOT NULL,
    result         TEXT NOT NULL CHECK (result IN ('SUCCESS','DENIED','ERROR')),
    error_message  TEXT,
    before_state   TEXT,  -- never includes secret values
    after_state    TEXT   -- never includes secret values
);

CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_namespace  ON audit_log(namespace);
CREATE INDEX IF NOT EXISTS idx_audit_identity   ON audit_log(identity_name);

-- GitOps agent state: one row per repository
CREATE TABLE IF NOT EXISTS gitops_state (
    repository_url        TEXT PRIMARY KEY NOT NULL,
    last_applied_commit   TEXT NOT NULL,
    last_applied_at       TIMESTAMPTZ NOT NULL,
    result                TEXT NOT NULL,
    error_message         TEXT
);

-- Identity / user registry
CREATE TABLE IF NOT EXISTS identities (
    id           TEXT PRIMARY KEY NOT NULL,
    name         TEXT NOT NULL UNIQUE,
    role         TEXT NOT NULL,
    namespace    TEXT,
    cert_serial  TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    metadata_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_identities_name ON identities(name);

-- Reconciliation state: last known Swarm state per resource (for drift detection)
CREATE TABLE IF NOT EXISTS reconcile_state (
    resource_id   TEXT PRIMARY KEY NOT NULL,
    kind          TEXT NOT NULL,
    namespace     TEXT NOT NULL,
    name          TEXT NOT NULL,
    swarm_id      TEXT,
    last_synced_at TIMESTAMPTZ NOT NULL,
    state_json    TEXT NOT NULL
);
