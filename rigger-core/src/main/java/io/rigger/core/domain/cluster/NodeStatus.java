package io.rigger.core.domain.cluster;

/**
 * Lifecycle status of a cluster node as tracked by Rigger.
 * Transitions: PENDING → PROVISIONING → ACTIVE → DRAINING → OFFLINE
 */
public enum NodeStatus {
    /** Node declared in rigger.cluster.yaml but not yet touched. */
    PENDING,
    /** SSH connection established; Docker installation or Swarm join in progress. */
    PROVISIONING,
    /** Node is healthy and accepting tasks. */
    ACTIVE,
    /** Node is being drained (tasks migrated away) before removal. */
    DRAINING,
    /** Node is unreachable or has left the Swarm. */
    OFFLINE
}
