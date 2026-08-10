package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Rigger Service exposure type. */
public enum ServiceType {
    /** Internal VIP only. Accessible within the overlay network. */
    CLUSTER_IP,
    /** Publishes a port on all Swarm nodes + configures Traefik routing. */
    LOAD_BALANCER;

    /**
     * Accepts both the Kubernetes-style spelling documented in the README/JSON Schema
     * ({@code ClusterIP}, {@code LoadBalancer}) and the Java enum constant names
     * ({@code CLUSTER_IP}, {@code LOAD_BALANCER}), case-insensitively.
     */
    @JsonCreator
    public static ServiceType fromValue(String value) {
        String normalized = value.replace("-", "_").toUpperCase();
        if (normalized.equals("CLUSTERIP")) normalized = "CLUSTER_IP";
        if (normalized.equals("LOADBALANCER")) normalized = "LOAD_BALANCER";
        return ServiceType.valueOf(normalized);
    }
}
