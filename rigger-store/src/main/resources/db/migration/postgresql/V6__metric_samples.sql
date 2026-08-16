-- Time series of sampled metrics, so the console can draw history instead of a single point.
-- Mirrors db/migration/sqlite/V6__metric_samples.sql.
--
-- value is DOUBLE PRECISION here, not REAL: SQLite's REAL is already an 8-byte IEEE double, but
-- Postgres's REAL is a 4-byte single-precision float — keeping REAL would silently lose precision
-- on every sample. DOUBLE PRECISION is the true equivalent of the SQLite column and of Java's
-- `double` field on MetricSampleEntity.
CREATE TABLE IF NOT EXISTS metric_samples (
    id         TEXT PRIMARY KEY NOT NULL,
    metric     TEXT NOT NULL,
    namespace  TEXT NOT NULL,
    name       TEXT NOT NULL,
    value      DOUBLE PRECISION NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_samples_series
    ON metric_samples (metric, namespace, name, sampled_at DESC);

CREATE INDEX IF NOT EXISTS idx_metric_samples_sampled_at
    ON metric_samples (sampled_at);
