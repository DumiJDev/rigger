package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Config;
import io.rigger.core.domain.resource.*;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Translates Rigger ConfigMap resources to Docker Configs using docker-java.
 *
 * <p>Docker Configs are immutable once created — there is no update API. Each distinct
 * content generates a new, uniquely-named Config ({@code rigger-<ns>-<name>-<contentHash>});
 * {@link ConfigMapController} creates a new version on content change and removes the old
 * version once nothing references it anymore, rather than mutating one in place.
 */
@Component
public class ConfigAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdapter.class);
    public static final String LABEL_NAMESPACE = "rigger.io/namespace";
    public static final String LABEL_NAME      = "rigger.io/name";
    public static final String LABEL_SPEC_HASH = "rigger.io/spec-hash";

    private final DockerClientFactory factory;

    public ConfigAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /** Lists all Docker Configs managed by Rigger, every version included. */
    public List<Config> listManaged() {
        try {
            return docker().listConfigsCmd()
                .withFilters(Map.of("label", List.of("rigger.io/managed=true")))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed configs", e);
        }
    }

    /** Finds the most recently created version of a Rigger-managed Config. */
    public Optional<Config> find(String namespace, String name) {
        try {
            return docker().listConfigsCmd()
                .withFilters(Map.of("label", List.of(
                    LABEL_NAMESPACE + "=" + namespace,
                    LABEL_NAME + "=" + name
                )))
                .exec()
                .stream()
                .max(Comparator.comparing(c -> c.getCreatedAt() != null ? c.getCreatedAt() : new Date(0)));
        } catch (Exception e) {
            throw new DockerApiException("Failed to find config " + namespace + "/" + name, e);
        }
    }

    /** Content hash used both in the Swarm-visible name and the {@code spec-hash} label. */
    public static String contentHash(ConfigMapSpec spec) {
        return Integer.toHexString(spec.data().hashCode());
    }

    /**
     * Builds the Swarm-visible Config name for a given namespace/name/content hash.
     *
     * <p>Uses {@code __} as a delimiter rather than {@code -}: Rigger namespace/name values are
     * restricted to {@code ^[a-z0-9][a-z0-9-]*[a-z0-9]$} (see the JSON Schemas in rigger-schema),
     * which never contains {@code __} — so {@link #parseFamilyKey} can split this back
     * unambiguously. This matters because docker-java 3.7.1's {@code ConfigSpec} doesn't
     * deserialise labels on list/find responses (a library gap), so cleanup can't rely on reading
     * labels back — it has to recover namespace/name from the name it already knows it created.
     */
    public static String swarmName(String namespace, String name, String hash) {
        return "rigger__" + namespace + "__" + name + "__" + hash;
    }

    /** Recovers "{namespace}/{name}" from a Config name produced by {@link #swarmName}, or null if it doesn't match. */
    public static String parseFamilyKey(String swarmName) {
        var parts = swarmName.split("__");
        if (parts.length != 4 || !"rigger".equals(parts[0])) return null;
        return parts[1] + "/" + parts[2];
    }

    /** Creates a new versioned Docker Config from a Rigger ConfigMap. */
    public String create(ObjectMeta meta, ConfigMapSpec spec) {
        String hash = contentHash(spec);
        String swarmName = swarmName(meta.namespace(), meta.name(), hash);
        log.info("Creating Docker Config: {}/{} -> {}", meta.namespace(), meta.name(), swarmName);
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(spec.data());

            var response = docker().createConfigCmd()
                .withName(swarmName)
                .withLabels(Map.of(
                    LABEL_NAMESPACE, meta.namespace(),
                    LABEL_NAME,      meta.name(),
                    "rigger.io/kind",      "ConfigMap",
                    "rigger.io/managed",   "true",
                    LABEL_SPEC_HASH, hash
                ))
                .withData(json)
                .exec();
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create config " + meta.qualifiedName(), e);
        }
    }

    /** Removes a Docker Config by ID. Fails if it is still attached to a running service. */
    public void delete(String configId) {
        log.info("Deleting Docker Config: {}", configId);
        try {
            docker().removeConfigCmd(configId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete config " + configId, e);
        }
    }
}
