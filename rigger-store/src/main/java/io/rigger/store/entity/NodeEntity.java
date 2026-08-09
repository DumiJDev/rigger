package io.rigger.store.entity;

import io.rigger.core.domain.cluster.NodeRole;
import io.rigger.core.domain.cluster.NodeStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for a cluster node.
 * Persists the desired + actual state of each node declared in rigger.cluster.yaml.
 */
@Entity
@Table(name = "cluster_nodes")
public class NodeEntity {

    @Id
    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String ip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeRole role;

    @Column(name = "is_primary", nullable = false, columnDefinition = "INTEGER")
    private boolean primary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeStatus status;

    @Column(nullable = false)
    private String clusterName;

    /** Labels serialised as JSON. */
    @Column(columnDefinition = "TEXT")
    private String labelsJson;

    @Column(columnDefinition = "TEXT")
    private Instant lastSeenAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column
    private String swarmNodeId;

    protected NodeEntity() {}

    public NodeEntity(String name, String ip, NodeRole role, boolean primary,
                      NodeStatus status, String clusterName) {
        this.name = name;
        this.ip = ip;
        this.role = role;
        this.primary = primary;
        this.status = status;
        this.clusterName = clusterName;
        this.createdAt = Instant.now();
    }

    public String getName() { return name; }
    public String getIp() { return ip; }
    public NodeRole getRole() { return role; }
    public boolean isPrimary() { return primary; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public String getClusterName() { return clusterName; }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSwarmNodeId() { return swarmNodeId; }
    public void setSwarmNodeId(String swarmNodeId) { this.swarmNodeId = swarmNodeId; }
}