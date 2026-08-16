-- Persisted operational events (resource applied/deleted/scaled, HPA scaling, pod failures,
-- node changes, GitOps syncs, reconciliation summaries).
--
-- RiggerEventBus publishes these in-memory only, so before this table the console's activity feed
-- would reset on every restart. This is distinct from audit_log: audit records *who asked for
-- what* (security record, append-only, never pruned), while this records *what the system did*
-- and is safe to prune by age/count.
CREATE TABLE IF NOT EXISTS events (
    id             TEXT PRIMARY KEY NOT NULL,
    type           TEXT NOT NULL,
    resource_kind  TEXT,
    resource_name  TEXT,
    namespace      TEXT,
    actor          TEXT,
    message        TEXT,
    occurred_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_occurred_at ON events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_namespace   ON events (namespace, occurred_at DESC);
