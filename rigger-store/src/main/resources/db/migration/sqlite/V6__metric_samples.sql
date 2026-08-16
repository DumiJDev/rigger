-- Time series of sampled metrics, so the console can draw history instead of a single point.
--
-- Before this table /api/v1/cluster/metrics and .../deployments/{name}/metrics were sampled per
-- request and nothing was kept, so any chart the console drew was lost on refresh. Storing the
-- series server-side means every client sees the same history, and a reload does not start over.
--
-- Like `events` and unlike `audit_log`, this is prunable by age: it feeds charts, not a security
-- record. MetricsSampler writes it and prunes it in the same job.
CREATE TABLE IF NOT EXISTS metric_samples (
    id         TEXT PRIMARY KEY NOT NULL,
    metric     TEXT NOT NULL,
    namespace  TEXT NOT NULL,
    name       TEXT NOT NULL,
    value      REAL NOT NULL,
    sampled_at TEXT NOT NULL
);

-- The only read pattern is "one series, most recent window first", which this index serves
-- end to end — the WHERE columns in order, then the ORDER BY.
CREATE INDEX IF NOT EXISTS idx_metric_samples_series
    ON metric_samples (metric, namespace, name, sampled_at DESC);

-- Pruning deletes by age across every series, so it needs its own index; without it the retention
-- job degrades into a full scan that grows with the table it is meant to bound.
CREATE INDEX IF NOT EXISTS idx_metric_samples_sampled_at
    ON metric_samples (sampled_at);
