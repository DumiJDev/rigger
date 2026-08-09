package io.rigger.events.model;

import io.rigger.core.domain.resource.ResourceRef;

/**
 * Fired when a manifest is successfully applied (create or update).
 */
public final class ResourceAppliedEvent extends RiggerEvent {
    private final ResourceRef resource;
    private final String appliedBy;
    private final boolean created; // true = new resource, false = update

    public ResourceAppliedEvent(ResourceRef resource, String appliedBy, boolean created) {
        super();
        this.resource = resource;
        this.appliedBy = appliedBy;
        this.created = created;
    }

    @Override public String type() { return created ? "resource.created" : "resource.updated"; }
    public ResourceRef resource() { return resource; }
    public String appliedBy() { return appliedBy; }
    public boolean isCreated() { return created; }
}