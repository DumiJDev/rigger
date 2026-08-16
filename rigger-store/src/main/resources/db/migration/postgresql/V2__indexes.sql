-- Additional indexes for common query patterns
-- Added as a separate migration to keep V1 clean. Mirrors db/migration/sqlite/V2__indexes.sql.

CREATE INDEX IF NOT EXISTS idx_resources_ns_name ON resources(namespace, name);
CREATE INDEX IF NOT EXISTS idx_audit_action       ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_reconcile_ns_kind  ON reconcile_state(namespace, kind);
