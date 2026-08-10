package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Secret;
import com.github.dockerjava.api.model.SecretSpec;
import io.rigger.core.domain.resource.ObjectMeta;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translates Rigger Secret resources to Docker Secrets using docker-java.
 *
 * SECURITY: Docker Swarm needs the real secret value to mount into containers — it has its
 * own at-rest encryption independent of Rigger's. Callers must DECRYPT values (via
 * SecretEncryptor) before calling {@link #create}; Rigger's AES-256-GCM layer only protects
 * values while they sit in Rigger's own SQLite store. Values are never logged here either way.
 */
@Component
public class SecretAdapter {

    private static final Logger log = LoggerFactory.getLogger(SecretAdapter.class);

    private final DockerClientFactory factory;

    public SecretAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /**
     * Creates a Docker Secret.
     * @param meta Resource metadata.
     * @param data Real (decrypted) values — this is what containers will see. Never logged.
     */
    public String create(ObjectMeta meta, Map<String, String> data) {
        // Log only keys, never values
        log.info("Creating Docker Secret: {}/{} (keys: {})", meta.namespace(), meta.name(), data.keySet());
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(data);

            var spec = new SecretSpec()
                .withName("rigger-" + meta.namespace() + "-" + meta.name())
                .withLabels(Map.of(
                    "rigger.io/namespace", meta.namespace(),
                    "rigger.io/name",      meta.name(),
                    "rigger.io/kind",      "Secret",
                    "rigger.io/managed",   "true"
                ))
                .withData(Base64.getEncoder().encodeToString(json));

            var response = docker().createSecretCmd(spec).exec();
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create secret " + meta.qualifiedName(), e);
        }
    }

    /** Lists managed secrets (metadata only — Docker never returns secret values). */
    public List<Secret> listManaged() {
        try {
            return docker().listSecretsCmd()
                .withLabelFilter(Map.of("rigger.io/managed", "true"))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed secrets", e);
        }
    }

    /** Finds a Rigger-managed Secret by namespace and name (metadata only). */
    public Optional<Secret> find(String namespace, String name) {
        try {
            return docker().listSecretsCmd()
                .withLabelFilter(Map.of(
                    "rigger.io/namespace", namespace,
                    "rigger.io/name", name
                ))
                .exec()
                .stream()
                .findFirst();
        } catch (Exception e) {
            throw new DockerApiException("Failed to find secret " + namespace + "/" + name, e);
        }
    }

    /** Removes a Docker Secret by ID. */
    public void delete(String secretId) {
        log.info("Deleting Docker Secret: {}", secretId);
        try {
            docker().removeSecretCmd(secretId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete secret " + secretId, e);
        }
    }
}
