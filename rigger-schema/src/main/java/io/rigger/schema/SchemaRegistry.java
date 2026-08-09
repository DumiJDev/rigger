package io.rigger.schema;

import java.net.URL;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of embedded JSON Schema files shipped inside the rigger-schema JAR.
 * Schemas are loaded from the classpath at runtime — no filesystem access needed.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link ManifestSchemaValidator} — validates manifest YAML before apply</li>
 *   <li>IDE plugins — the schemas are also exposed as static resources</li>
 * </ul>
 */
public final class SchemaRegistry {

    private static final Map<String, String> KIND_TO_SCHEMA = Map.of(
        "Cluster",    "schema/cluster.schema.json",
        "Deployment", "schema/deployment.schema.json",
        "Service",    "schema/service.schema.json",
        "ConfigMap",  "schema/configmap.schema.json",
        "Secret",     "schema/secret.schema.json"
    );

    private SchemaRegistry() {}

    /**
     * Returns the classpath URL of the JSON Schema for the given kind.
     *
     * @param kind Resource kind string (e.g. "Deployment").
     * @return Optional URL, empty if kind has no registered schema.
     */
    public static Optional<URL> schemaUrlFor(String kind) {
        String resource = KIND_TO_SCHEMA.get(kind);
        if (resource == null) return Optional.empty();
        return Optional.ofNullable(SchemaRegistry.class.getClassLoader().getResource(resource));
    }

    /** Returns all registered kind names. */
    public static java.util.Set<String> registeredKinds() {
        return KIND_TO_SCHEMA.keySet();
    }
}
