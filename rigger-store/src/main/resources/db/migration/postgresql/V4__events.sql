-- Persisted operational events (resource applied/deleted/scaled, HPA scaling, pod failures,
-- node changes, GitOps syncs, reconciliation summaries). Mirrors db/migration/sqlite/V4__events.sql.
CREATE TABLE IF NOT EXISTS events (
    id             TEXT PRIMARY KEY NOT NULL,
    type           TEXT NOT NULL,
    resource_kind  TEXT,
    resource_name  TEXT,
    namespace      TEXT,
    actor          TEXT,
    message        TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_occurred_at ON events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_namespace   ON events (namespace, occurred_at DESC);
