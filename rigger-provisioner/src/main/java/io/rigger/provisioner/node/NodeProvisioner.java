package io.rigger.provisioner.node;

import io.rigger.core.domain.cluster.*;
import io.rigger.provisioner.docker.DockerInstaller;
import io.rigger.provisioner.ssh.*;
import io.rigger.provisioner.swarm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Provisions individual cluster nodes.
 * Each node is provisioned concurrently using Virtual Threads.
 *
 * <p>Provisioning steps per node:
 * <ol>
 *   <li>Verify SSH connectivity</li>
 *   <li>Check/install Docker Engine</li>
 *   <li>Join the Swarm (as manager or worker)</li>
 * </ol>
 */
@Component
public class NodeProvisioner {

    private static final Logger log = LoggerFactory.getLogger(NodeProvisioner.class);

    private final RiggerSshClient sshClient;
    private final DockerInstaller dockerInstaller;
    private final SwarmInitializer swarmInitializer;

    public NodeProvisioner(RiggerSshClient sshClient,
                           DockerInstaller dockerInstaller,
                           SwarmInitializer swarmInitializer) {
        this.sshClient = sshClient;
        this.dockerInstaller = dockerInstaller;
        this.swarmInitializer = swarmInitializer;
    }

    /**
     * Provisions a list of non-primary nodes concurrently.
     * Primary node must already be provisioned (Swarm must exist to supply tokens).
     *
     * @param nodes    Nodes to provision.
     * @param spec     Full cluster spec (for resolving credentials and docker version).
     * @param tokens   Swarm join tokens from the primary manager.
     * @param primaryIp IP of the primary manager for Swarm join.
     * @return Map of node name → provisioning result.
     */
    public Map<String, NodeProvisionResult> provisionAll(
            List<NodeSpec> nodes,
            ClusterSpec spec,
            SwarmTokens tokens,
            String primaryIp) {

        var results = new ConcurrentHashMap<String, NodeProvisionResult>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = nodes.stream()
                    .map(node -> executor.submit(() -> {
                        var result = provisionNode(node, spec, tokens, primaryIp);
                        results.put(node.name(), result);
                        return result;
                    }))
                    .toList();

            // Wait for all — collect any exceptions
            for (var future : futures) {
                try {
                    future.get(10, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    log.error("Node provisioning timed out");
                } catch (ExecutionException e) {
                    log.error("Node provisioning threw unexpected exception", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Provisioning interrupted");
                }
            }
        }

        return Map.copyOf(results);
    }

    /**
     * Provisions the primary manager node.
     * Initialises the Swarm and returns the join tokens.
     *
     * @param primaryNode The node with primary=true.
     * @param spec        Full cluster spec.
     * @return SwarmTokens for other nodes to join.
     */
    public SwarmTokens provisionPrimary(NodeSpec primaryNode, ClusterSpec spec) {
        log.info("Provisioning primary manager: {} ({})", primaryNode.name(), primaryNode.ip());
        var creds = spec.resolveCredentials(primaryNode);

        try (var session = sshClient.connect(primaryNode.ip(), creds)) {
            dockerInstaller.installIfAbsent(session, spec.docker(), primaryNode.name());
            return swarmInitializer.initSwarm(session, primaryNode.ip(), primaryNode.name());
        } catch (Exception e) {
            throw new io.rigger.core.exception.ProvisioningException(
                primaryNode.name(), "Primary manager provisioning failed", e);
        }
    }

    // ── private ───────────────────────────────────────────────────────────

    private NodeProvisionResult provisionNode(
            NodeSpec node, ClusterSpec spec, SwarmTokens tokens, String primaryIp) {

        var start = Instant.now();
        var creds = spec.resolveCredentials(node);
        log.info("Provisioning {} ({}) as {}", node.name(), node.ip(), node.role());

        try (var session = sshClient.connect(node.ip(), creds)) {
            boolean dockerInstalled = dockerInstaller.installIfAbsent(session, spec.docker(), node.name());

            if (node.role() == NodeRole.MANAGER) {
                swarmInitializer.joinAsManager(session, node.name(), tokens, primaryIp);
            } else {
                swarmInitializer.joinAsWorker(session, node.name(), tokens, primaryIp);
            }

            var swarmNodeId = getSwarmNodeId(session);
            var duration = Duration.between(start, Instant.now());
            log.info("Node {} provisioned in {}s", node.name(), duration.getSeconds());
            return NodeProvisionResult.success(node.name(), node.role(), dockerInstalled, swarmNodeId, duration);

        } catch (Exception e) {
            var duration = Duration.between(start, Instant.now());
            log.error("Failed to provision node {}: {}", node.name(), e.getMessage());
            return NodeProvisionResult.failure(node.name(), node.role(), duration, e.getMessage());
        }
    }

    private String getSwarmNodeId(io.rigger.provisioner.ssh.SshSession session) {
        var result = session.exec("docker info --format '{{.Swarm.NodeID}}' 2>/dev/null");
        return result.isSuccess() ? result.trimmedOutput() : null;
    }
}
