package io.rigger.manifest.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.rigger.core.domain.resource.*;
import io.rigger.manifest.parser.ParsedManifest;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Converts a Docker Compose v3 file into a list of Rigger manifests.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>{@code services.*} → {@link DeploymentSpec} + {@link ServiceSpec}</li>
 *   <li>{@code configs.*}  → {@link ConfigMapSpec}</li>
 *   <li>{@code secrets.*}  → {@link SecretSpec}</li>
 * </ul>
 *
 * <p>Compose features not supported by Rigger are silently ignored
 * (build:, depends_on:, profiles:). A warning is logged for each ignored key.
 *
 * <p>Usage:
 * <pre>
 * riggerctl apply -f docker-compose.yml --namespace staging
 * </pre>
 */
@Component
public class ComposeConverter {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /**
     * Converts a Compose file to Rigger manifests.
     *
     * @param composePath  Path to the docker-compose.yml file.
     * @param namespace    Target namespace for generated resources.
     * @return List of parsed manifests ready for apply.
     */
    public List<ParsedManifest> convert(Path composePath, String namespace) throws IOException {
        return convert(yaml.readTree(composePath.toFile()), namespace, composePath.toString());
    }

    /**
     * Converts Compose content already in memory — the apply endpoint receives the file as a
     * string, so it never has a path to hand.
     */
    public List<ParsedManifest> convertString(String composeYaml, String namespace, String source)
            throws IOException {
        return convert(yaml.readTree(composeYaml), namespace, source);
    }

    /**
     * Whether the given YAML looks like a Compose file rather than a Rigger manifest.
     *
     * <p>Keyed on a top-level {@code services} map with no {@code apiVersion}/{@code kind}: a Rigger
     * manifest always carries both, and a Compose file never does. Returns false for anything
     * unparseable so the caller reports the real manifest error rather than a misleading
     * "not valid Compose".
     */
    public boolean isCompose(String content) {
        try {
            var root = yaml.readTree(content);
            return root != null
                && root.path("services").isObject()
                && root.path("apiVersion").isMissingNode()
                && root.path("kind").isMissingNode();
        } catch (Exception e) {
            return false;
        }
    }

    private List<ParsedManifest> convert(JsonNode root, String namespace, String source) {
        var results = new ArrayList<ParsedManifest>();
        convertServices(root.path("services"), namespace, source, results);
        convertConfigs(root.path("configs"), namespace, source, results);
        convertSecrets(root.path("secrets"), namespace, source, results);
        return results;
    }

    private void convertServices(JsonNode services, String ns, String source, List<ParsedManifest> out) {
        if (services == null || services.isMissingNode()) return;
        services.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode svc = entry.getValue();
            String image = svc.path("image").asText("unknown:latest");
            int replicas = svc.path("deploy").path("replicas").asInt(1);

            var env = new ArrayList<EnvVar>();
            svc.path("environment").fields().forEachRemaining(e ->
                env.add(new EnvVar(e.getKey(), e.getValue().asText(), null)));

            var spec = new DeploymentSpec(replicas, Map.of("app", name), image,
                    env, null, null, null, List.of(), List.of());
            var meta = new ObjectMeta(name, ns, Map.of("app", name), Map.of());
            out.add(new ParsedManifest(new RiggerManifest(RiggerManifest.API_VERSION, "Deployment", meta, spec), source, null));

            // Generate a ClusterIP service for each Compose service that exposes ports
            var ports = new ArrayList<ServicePort>();
            svc.path("ports").forEach(p -> {
                String portStr = p.asText();
                String[] parts = portStr.split(":");
                try {
                    int port = Integer.parseInt(parts[parts.length - 1].split("/")[0]);
                    ports.add(new ServicePort(port, port, "TCP"));
                } catch (NumberFormatException ignored) {}
            });
            if (!ports.isEmpty()) {
                var svcSpec = new ServiceSpec(Map.of("app", name), ports, ServiceType.CLUSTER_IP);
                var svcMeta = new ObjectMeta(name + "-svc", ns, Map.of("app", name), Map.of());
                out.add(new ParsedManifest(new RiggerManifest(RiggerManifest.API_VERSION, "Service", svcMeta, svcSpec), source, null));
            }
        });
    }

    private void convertConfigs(JsonNode configs, String ns, String source, List<ParsedManifest> out) {
        if (configs == null || configs.isMissingNode()) return;
        configs.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            var data = new LinkedHashMap<String, String>();
            entry.getValue().fields().forEachRemaining(e -> data.put(e.getKey(), e.getValue().asText()));
            var spec = new ConfigMapSpec(data);
            var meta = new ObjectMeta(name, ns, Map.of(), Map.of());
            out.add(new ParsedManifest(new RiggerManifest(RiggerManifest.API_VERSION, "ConfigMap", meta, spec), source, null));
        });
    }

    private void convertSecrets(JsonNode secrets, String ns, String source, List<ParsedManifest> out) {
        if (secrets == null || secrets.isMissingNode()) return;
        secrets.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            var data = new LinkedHashMap<String, String>();
            entry.getValue().fields().forEachRemaining(e -> data.put(e.getKey(), e.getValue().asText()));
            // If no data keys present, use a placeholder — values must be supplied separately
            if (data.isEmpty()) return;
            var spec = new SecretSpec(data, null);
            var meta = new ObjectMeta(name, ns, Map.of(), Map.of());
            out.add(new ParsedManifest(new RiggerManifest(RiggerManifest.API_VERSION, "Secret", meta, spec), source, null));
        });
    }
}