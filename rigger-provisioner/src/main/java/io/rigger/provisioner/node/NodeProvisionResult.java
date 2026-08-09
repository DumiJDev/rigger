package io.rigger.provisioner.node;

import io.rigger.core.domain.cluster.NodeRole;
import java.time.Duration;
import java.time.Instant;

/**
 * Outcome of provisioning a single node.
 *
 * @param nodeName       Node identifier.
 * @param role           MANAGER or WORKER.
 * @param success        Whether provisioning completed successfully.
 * @param dockerInstalled Whether Docker was newly installed (false = was already present).
 * @param swarmNodeId    Docker Swarm node ID assigned after joining.
 * @param duration       Total time taken.
 * @param errorMessage   Set if success=false.
 */
public record NodeProvisionResult(
        String nodeName,
        NodeRole role,
        boolean success,
        boolean dockerInstalled,
        String swarmNodeId,
        Duration duration,
        String errorMessage
) {
    public static NodeProvisionResult success(
            String nodeName, NodeRole role, boolean dockerInstalled,
            String swarmNodeId, Duration duration) {
        return new NodeProvisionResult(nodeName, role, true, dockerInstalled, swarmNodeId, duration, null);
    }

    public static NodeProvisionResult failure(String nodeName, NodeRole role, Duration duration, String error) {
        return new NodeProvisionResult(nodeName, role, false, false, null, duration, error);
    }
}
