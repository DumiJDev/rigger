package io.rigger.core.domain.resource;

/**
 * Lightweight reference to any Rigger resource.
 * Used in events, audit entries, and cross-resource links.
 *
 * @param kind      Resource kind.
 * @param namespace Namespace of the resource.
 * @param name      Name of the resource.
 */
public record ResourceRef(
        ResourceKind kind,
        String namespace,
        String name
) {
    public ResourceRef {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase() + "/" + namespace + "/" + name;
    }
}
