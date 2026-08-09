package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Generic JPA entity for all Rigger workload resources (Deployment, Service, ConfigMap, Secret, Pod).
 * The spec is stored as a JSON blob — this avoids a table per resource kind
 * while keeping the schema stable as new kinds are added.
 *
 * The UNIQUE constraint on (kind, namespace, name) enforces resource identity.
 */
@Entity
@Table(name = "resources",
       uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "namespace", "name"}))
public class ResourceEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String name;

    /** Full manifest spec serialised as JSON. Secret values are encrypted before storage. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String specJson;

    /** Serialised labels for fast label-selector queries. */
    @Column(columnDefinition = "TEXT")
    private String labelsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    /** The identity name that last applied this resource. */
    @Column
    private String appliedBy;

    protected ResourceEntity() {}

    public ResourceEntity(String id, String kind, String namespace, String name,
                          String specJson, String labelsJson, String appliedBy) {
        this.id = id;
        this.kind = kind;
        this.namespace = namespace;
        this.name = name;
        this.specJson = specJson;
        this.labelsJson = labelsJson;
        this.appliedBy = appliedBy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getKind() { return kind; }
    public String getNamespace() { return namespace; }
    public String getName() { return name; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String specJson) { this.specJson = specJson; this.updatedAt = Instant.now(); }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getAppliedBy() { return appliedBy; }
    public void setAppliedBy(String appliedBy) { this.appliedBy = appliedBy; }
}