package io.rigger.provisioner.cluster;

import io.rigger.provisioner.node.NodeProvisionResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Summary result of a {@code rigger cluster up} operation.
 */
public record ClusterUpResult(
        String clusterName,
        boolean success,
        Map<String, NodeProvisionResult> nodeResults,
        Duration totalDuration,
        String swarmId
) {
    public long successCount() {
        return nodeResults.values().stream().filter(NodeProvisionResult::success).count();
    }
    public long failureCount() {
        return nodeResults.values().stream().filter(r -> !r.success()).count();
    }
    public List<String> failedNodes() {
        return nodeResults.entrySet().stream()
                .filter(e -> !e.getValue().success())
                .map(Map.Entry::getKey)
                .toList();
    }
}
