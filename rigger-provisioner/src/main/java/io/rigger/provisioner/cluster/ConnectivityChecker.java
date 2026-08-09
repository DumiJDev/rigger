package io.rigger.provisioner.cluster;

import io.rigger.core.domain.cluster.ClusterSpec;
import io.rigger.core.domain.cluster.NodeSpec;
import io.rigger.provisioner.ssh.RiggerSshClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * Checks SSH connectivity to all cluster nodes in parallel before provisioning starts.
 * Fails fast if any node is unreachable, preventing partial cluster state.
 */
@Component
public class ConnectivityChecker {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityChecker.class);

    private final RiggerSshClient sshClient;

    public ConnectivityChecker(RiggerSshClient sshClient) {
        this.sshClient = sshClient;
    }

    /**
     * Tests SSH connectivity to all declared nodes concurrently.
     *
     * @param spec The cluster spec with all node definitions.
     * @return Map of node name → reachable (true/false).
     */
    public Map<String, Boolean> checkAll(ClusterSpec spec) {
        var results = new ConcurrentHashMap<String, Boolean>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = spec.nodes().stream()
                    .map(node -> executor.submit(() -> {
                        var creds = spec.resolveCredentials(node);
                        boolean reachable = sshClient.isReachable(node.ip(), creds);
                        results.put(node.name(), reachable);
                        log.info("SSH check {} ({}): {}", node.name(), node.ip(),
                                reachable ? "✓ reachable" : "✗ unreachable");
                        return reachable;
                    }))
                    .toList();

            futures.forEach(f -> {
                try { f.get(30, TimeUnit.SECONDS); }
                catch (Exception e) { log.warn("Connectivity check timed out or failed"); }
            });
        }

        return Map.copyOf(results);
    }

    /**
     * Returns true only if all nodes are reachable.
     * Logs details of any unreachable nodes.
     */
    public boolean allReachable(Map<String, Boolean> results) {
        var unreachable = results.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .toList();

        if (!unreachable.isEmpty()) {
            log.error("Unreachable nodes: {}", unreachable);
            return false;
        }
        return true;
    }
}
