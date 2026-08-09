package io.rigger.events.model;

/** Fired at the end of each reconciliation cycle. Summary of what changed. */
public final class ReconciliationEvent extends RiggerEvent {
    private final int created;
    private final int updated;
    private final int deleted;
    private final int errors;
    private final long durationMs;

    public ReconciliationEvent(int created, int updated, int deleted, int errors, long durationMs) {
        super();
        this.created = created; this.updated = updated;
        this.deleted = deleted; this.errors = errors; this.durationMs = durationMs;
    }

    @Override public String type() { return "reconciliation.cycle"; }
    public int created() { return created; }
    public int updated() { return updated; }
    public int deleted() { return deleted; }
    public int errors() { return errors; }
    public long durationMs() { return durationMs; }
}