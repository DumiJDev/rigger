package io.rigger.provisioner.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.rigger.core.domain.cluster.*;
import io.rigger.core.exception.ManifestValidationException;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Parses {@code rigger.cluster.yaml} into a {@link ClusterSpec}.
 *
 * <p>The YAML structure mirrors the ClusterSpec record:
 * <pre>
 * apiVersion: rigger.io/v1
 * kind: Cluster
 * metadata:
 *   name: prod-angola
 * spec:
 *   docker:
 *     version: "26.1"
 *     channel: stable
 *   defaults:
 *     ssh:
 *       user: ubuntu
 *       privateKeyPath: ~/.ssh/rigger_id_ed25519
 *       port: 22
 *   nodes:
 *     - name: manager-01
 *       ip: 10.0.0.10
 *       role: manager
 *       primary: true
 * </pre>
 */
@Component
public class ClusterManifestParser {

    private static final String EXPECTED_API_VERSION = "rigger.io/v1";
    private static final String EXPECTED_KIND = "Cluster";

    private final ObjectMapper yaml;

    public ClusterManifestParser() {
        this.yaml = new ObjectMapper(new YAMLFactory());
        this.yaml.findAndRegisterModules();
    }

    /**
     * Parses a cluster manifest from a file path.
     *
     * @param path Path to rigger.cluster.yaml.
     * @return Validated ClusterSpec.
     * @throws IOException if the file cannot be read.
     * @throws ManifestValidationException if the manifest is invalid.
     */
    public ClusterSpec parse(Path path) throws IOException {
        String content = Files.readString(path);
        return parseString(content);
    }

    public ClusterSpec parseString(String yaml) throws IOException {
        var root = this.yaml.readTree(yaml);

        String apiVersion = root.path("apiVersion").asText();
        String kind = root.path("kind").asText();
        var violations = new ArrayList<String>();

        if (!EXPECTED_API_VERSION.equals(apiVersion))
            violations.add("apiVersion must be '" + EXPECTED_API_VERSION + "', got: " + apiVersion);
        if (!EXPECTED_KIND.equals(kind))
            violations.add("kind must be 'Cluster', got: " + kind);
        if (!violations.isEmpty())
            throw new ManifestValidationException(violations);

        String clusterName = root.path("metadata").path("name").asText(null);
        if (clusterName == null || clusterName.isBlank())
            violations.add("metadata.name is required");

        var specNode = root.path("spec");

        // Parse docker spec
        DockerSpec dockerSpec = DockerSpec.DEFAULT;
        if (!specNode.path("docker").isMissingNode()) {
            dockerSpec = new DockerSpec(
                specNode.path("docker").path("version").asText("26.1"),
                specNode.path("docker").path("channel").asText("stable")
            );
        }

        // Parse SSH defaults
        var sshNode = specNode.path("defaults").path("ssh");
        if (sshNode.isMissingNode())
            violations.add("spec.defaults.ssh is required");
        if (!violations.isEmpty())
            throw new ManifestValidationException(violations);

        var defaultSsh = new SshCredentials(
            sshNode.path("user").asText(),
            sshNode.path("privateKeyPath").asText(),
            sshNode.path("port").asInt(22)
        );
        var defaults = new ClusterDefaults(defaultSsh);

        // Parse nodes
        var nodesNode = specNode.path("nodes");
        if (nodesNode.isMissingNode() || !nodesNode.isArray() || nodesNode.isEmpty())
            violations.add("spec.nodes must be a non-empty list");
        if (!violations.isEmpty())
            throw new ManifestValidationException(violations);

        var nodes = new ArrayList<NodeSpec>();
        nodesNode.forEach(n -> {
            SshCredentials nodeSsh = null;
            if (!n.path("ssh").isMissingNode()) {
                nodeSsh = new SshCredentials(
                    n.path("ssh").path("user").asText(defaultSsh.user()),
                    n.path("ssh").path("privateKeyPath").asText(defaultSsh.privateKeyPath()),
                    n.path("ssh").path("port").asInt(22)
                );
            }
            var role = NodeRole.valueOf(n.path("role").asText("worker").toUpperCase());
            nodes.add(new NodeSpec(
                n.path("name").asText(),
                n.path("ip").asText(),
                role,
                n.path("primary").asBoolean(false),
                nodeSsh,
                Map.of(),
                n.path("autoProvision").asBoolean(false)
            ));
        });

        // Parse dev mode
        DevMode devMode = DevMode.DISABLED;
        if (!specNode.path("dev").isMissingNode()) {
            devMode = new DevMode(
                specNode.path("dev").path("enabled").asBoolean(false),
                specNode.path("dev").path("dockerSocket").asText("/var/run/docker.sock")
            );
        }

        return new ClusterSpec(clusterName, null, dockerSpec, defaults, nodes, devMode, Map.of());
    }
}
