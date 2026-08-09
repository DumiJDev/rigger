package io.rigger.events.model;

import io.rigger.core.domain.resource.ResourceRef;

/** Fired when a resource is successfully deleted. */
public final class ResourceDeletedEvent extends RiggerEvent {
    private final ResourceRef resource;
    private final String deletedBy;

    public ResourceDeletedEvent(ResourceRef resource, String deletedBy) {
        super();
        this.resource = resource;
        this.deletedBy = deletedBy;
    }

    @Override public String type() { return "resource.deleted"; }
    public ResourceRef resource() { return resource; }
    public String deletedBy() { return deletedBy; }
}