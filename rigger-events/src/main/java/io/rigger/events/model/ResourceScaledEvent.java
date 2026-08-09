package io.rigger.events.model;

import io.rigger.core.domain.resource.ResourceRef;

/** Fired when a Deployment is scaled (manually or by HPA). */
public final class ResourceScaledEvent extends RiggerEvent {
    private final ResourceRef resource;
    private final int previousReplicas;
    private final int newReplicas;
    private final String reason; // "manual" | "hpa" | "node-scaler"

    public ResourceScaledEvent(ResourceRef resource, int previousReplicas, int newReplicas, String reason) {
        super();
        this.resource = resource;
        this.previousReplicas = previousReplicas;
        this.newReplicas = newReplicas;
        this.reason = reason;
    }

    @Override public String type() { return "resource.scaled"; }
    public ResourceRef resource() { return resource; }
    public int previousReplicas() { return previousReplicas; }
    public int newReplicas() { return newReplicas; }
    public String reason() { return reason; }
}