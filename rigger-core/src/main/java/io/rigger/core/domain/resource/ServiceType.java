package io.rigger.core.domain.resource;

/** Rigger Service exposure type. */
public enum ServiceType {
    /** Internal VIP only. Accessible within the overlay network. */
    CLUSTER_IP,
    /** Publishes a port on all Swarm nodes + configures Traefik routing. */
    LOAD_BALANCER
}
