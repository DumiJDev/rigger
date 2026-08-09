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
 */
@Component
public class ConfigAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdapter.class);

    private final DockerClientFactory factory;

    public ConfigAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /** Lists all Docker Configs managed by Rigger. */
    public List<Config> listManaged() {
        try {
            return docker().listConfigsCmd()
                .withFilters(Map.of("label", List.of("rigger.io/managed=true")))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed configs", e);
        }
    }

    /** Creates a Docker Config from a Rigger ConfigMap. */
    public String create(ObjectMeta meta, ConfigMapSpec spec) {
        log.info("Creating Docker Config: {}/{}", meta.namespace(), meta.name());
        try {
            // Encode the key/value map as JSON bytes
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(spec.data());

            var response = docker().createConfigCmd()
                .withName("rigger-" + meta.namespace() + "-" + meta.name())
                .withLabels(Map.of(
                    "rigger.io/namespace", meta.namespace(),
                    "rigger.io/name",      meta.name(),
                    "rigger.io/kind",      "ConfigMap",
                    "rigger.io/managed",   "true"
                ))
                .withData(json)
                .exec();
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create config " + meta.qualifiedName(), e);
        }
    }

    /** Removes a Docker Config by ID. */
    public void delete(String configId) {
        log.info("Deleting Docker Config: {}", configId);
        try {
            docker().removeConfigCmd(configId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete config " + configId, e);
        }
    }
}
