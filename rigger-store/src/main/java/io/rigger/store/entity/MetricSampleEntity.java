package io.rigger.store.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One sampled metric value at one instant — a single point of a time series.
 *
 * <p>A series is identified by the triple {@code (metric, namespace, name)}. Cluster-wide metrics
 * use {@code "cluster"} for both namespace and name rather than leaving them null, so every read
 * path can use the same query and index without a null-handling special case.
 *
 * <p>Prunable by age, like {@link EventEntity} and unlike {@link AuditEntryEntity}: this exists to
 * draw charts, not to be a record of who did what.
 */
@Entity
@Table(name = "metric_samples")
public class MetricSampleEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false)
    private String metric;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String name;

    // REAL to match the SQLite column: every metric here is a rate or a count that charts as a
    // number, so one numeric type covers both rather than two columns or a tagged union.
    @Column(nullable = false, columnDefinition = "REAL")
    private double value;

    // TEXT to match the SQLite column type — Hibernate's schema validation is strict about this
    // even though SQLite itself is dynamically typed.
    @Column(name = "sampled_at", nullable = false, columnDefinition = "TEXT")
    private Instant sampledAt;

    protected MetricSampleEntity() { }

    public MetricSampleEntity(String id, String metric, String namespace, String name,
                              double value, Instant sampledAt) {
        this.id = id;
        this.metric = metric;
        this.namespace = namespace;
        this.name = name;
        this.value = value;
        this.sampledAt = sampledAt;
    }

    public String getId()         { return id; }
    public String getMetric()     { return metric; }
    public String getNamespace()  { return namespace; }
    public String getName()       { return name; }
    public double getValue()      { return value; }
    public Instant getSampledAt() { return sampledAt; }
}
