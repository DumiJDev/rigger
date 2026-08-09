package io.rigger.core.exception;

import io.rigger.core.domain.resource.ResourceKind;

/** Thrown when a requested resource does not exist in the store. */
public class ResourceNotFoundException extends RiggerException {
    public ResourceNotFoundException(ResourceKind kind, String namespace, String name) {
        super(kind.name().toLowerCase() + " '" + namespace + "/" + name + "' not found");
    }
}
