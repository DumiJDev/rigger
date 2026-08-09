package io.rigger.manifest.parser;

import io.rigger.core.domain.resource.*;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;

/**
 * Maps resource kind strings to their spec classes.
 * Add a new entry here when introducing a new resource kind.
 */
@Component
public class KindRegistry {

    private static final Map<String, Class<?>> KIND_TO_SPEC = Map.of(
        "Deployment", DeploymentSpec.class,
        "Service",    ServiceSpec.class,
        "ConfigMap",  ConfigMapSpec.class,
        "Secret",     SecretSpec.class
    );

    /**
     * Returns the spec class for the given kind string.
     *
     * @param kind Kind as declared in the manifest YAML.
     * @return Optional containing the spec class, empty if kind is unknown.
     */
    public Optional<Class<?>> specClassFor(String kind) {
        return Optional.ofNullable(KIND_TO_SPEC.get(kind));
    }

    public boolean isKnown(String kind) {
        return KIND_TO_SPEC.containsKey(kind);
    }
}