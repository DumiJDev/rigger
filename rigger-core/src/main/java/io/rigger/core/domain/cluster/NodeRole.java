package io.rigger.core.domain.cluster;

/**
 * Role of a node inside the Docker Swarm cluster.
 * MANAGER nodes participate in the Raft consensus quorum.
 * WORKER nodes only execute container tasks.
 */
public enum NodeRole {
    MANAGER,
    WORKER
}
