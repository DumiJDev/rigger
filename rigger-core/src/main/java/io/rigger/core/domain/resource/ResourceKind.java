package io.rigger.core.domain.resource;

/**
 * All supported resource kinds in the Rigger resource model.
 * Each kind maps to a concrete spec class and a Docker Swarm primitive.
 */
public enum ResourceKind {
    CLUSTER,
    DEPLOYMENT,
    REPLICA_SET,
    SERVICE,
    CONFIG_MAP,
    SECRET,
    POD,
    NAMESPACE,
    HPA
}
