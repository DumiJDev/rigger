package io.rigger.events.model;

import io.rigger.core.domain.resource.ResourceRef;
import io.rigger.core.domain.security.AuditAction;
import java.time.Instant;

/**
 * Base class for all internal Rigger events.
 * Published via {@link io.rigger.events.bus.RiggerEventBus} and consumed by
 * the operator, API watch endpoints, and UI streaming.
 *
 * <p>Events are in-memory only — they are not persisted. The audit log
 * (in rigger-store) is the durable record of what happened.
 */
public abstract sealed class RiggerEvent
    permits ResourceAppliedEvent, ResourceDeletedEvent, ResourceScaledEvent,
            PodFailedEvent, NodeAddedEvent, NodeDrainedEvent,
            HpaScaledEvent, ReconciliationEvent, GitOpsSyncEvent {

    private final String eventId;
    private final Instant occurredAt;

    protected RiggerEvent() {
        this.eventId = io.rigger.core.util.UlidGenerator.generate();
        this.occurredAt = Instant.now();
    }

    public String eventId() { return eventId; }
    public Instant occurredAt() { return occurredAt; }

    /** Human-readable event type for logging and SSE stream discrimination. */
    public abstract String type();
}