package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for a persisted operational event — what the system did, as opposed to
 * {@link AuditEntryEntity}, which records who asked for it.
 *
 * <p>Unlike the audit log this is prunable: it exists to power the console's activity feed, not to
 * be a security record.
 */
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false)
    private String type;

    @Column(name = "resource_kind")
    private String resourceKind;

    @Column(name = "resource_name")
    private String resourceName;

    @Column
    private String namespace;

    @Column
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String message;

    // TEXT to match the SQLite column type — Hibernate's schema validation is strict about this
    // even though SQLite itself is dynamically typed.
    @Column(name = "occurred_at", nullable = false, columnDefinition = "TEXT")
    private Instant occurredAt;

    protected EventEntity() { }

    public EventEntity(String id, String type, String resourceKind, String resourceName,
                       String namespace, String actor, String message, Instant occurredAt) {
        this.id = id;
        this.type = type;
        this.resourceKind = resourceKind;
        this.resourceName = resourceName;
        this.namespace = namespace;
        this.actor = actor;
        this.message = message;
        this.occurredAt = occurredAt;
    }

    public String getId()            { return id; }
    public String getType()          { return type; }
    public String getResourceKind()  { return resourceKind; }
    public String getResourceName()  { return resourceName; }
    public String getNamespace()     { return namespace; }
    public String getActor()         { return actor; }
    public String getMessage()       { return message; }
    public Instant getOccurredAt()   { return occurredAt; }
}
