package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.SwarmNode;
import com.github.dockerjava.api.model.SwarmNodeAvailability;
import com.github.dockerjava.api.model.SwarmNodeSpec;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/**
 * Reads and manages Docker Swarm nodes using docker-java.
 */
@Component
public class NodeAdapter {

    private static final Logger log = LoggerFactory.getLogger(NodeAdapter.class);

    private final DockerClientFactory factory;

    public NodeAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /** Lists all nodes in the Swarm. */
    public List<SwarmNode> listNodes() {
        try {
            return docker().listSwarmNodesCmd().exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list Swarm nodes", e);
        }
    }

    /** Finds a node by its Swarm ID. */
    public Optional<SwarmNode> findById(String nodeId) {
        try {
            return docker().listSwarmNodesCmd()
                .withIdFilter(List.of(nodeId))
                .exec()
                .stream()
                .findFirst();
        } catch (Exception e) {
            throw new DockerApiException("Failed to find node " + nodeId, e);
        }
    }

    /** Sets availability to DRAIN — Docker migrates tasks away. Call before removal. */
    public void drain(String nodeId) {
        log.info("Draining Swarm node: {}", nodeId);
        try {
            var node = findById(nodeId).orElseThrow(
                () -> new DockerApiException("Node not found: " + nodeId));
            var spec = node.getSpec() != null ? node.getSpec() : new SwarmNodeSpec();
            spec.withAvailability(SwarmNodeAvailability.DRAIN);
            long version = node.getVersion() != null ? node.getVersion().getIndex() : 0L;
            docker().updateSwarmNodeCmd()
                .withSwarmNodeId(nodeId)
                .withVersion(version)
                .withSwarmNodeSpec(spec)
                .exec();
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            throw new DockerApiException("Failed to drain node " + nodeId, e);
        }
    }

    /** Re-activates a drained node. */
    public void reactivate(String nodeId) {
        log.info("Reactivating Swarm node: {}", nodeId);
        try {
            var node = findById(nodeId).orElseThrow(
                () -> new DockerApiException("Node not found: " + nodeId));
            var spec = node.getSpec() != null ? node.getSpec() : new SwarmNodeSpec();
            spec.withAvailability(SwarmNodeAvailability.ACTIVE);
            long version = node.getVersion() != null ? node.getVersion().getIndex() : 0L;
            docker().updateSwarmNodeCmd()
                .withSwarmNodeId(nodeId)
                .withVersion(version)
                .withSwarmNodeSpec(spec)
                .exec();
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            throw new DockerApiException("Failed to reactivate node " + nodeId, e);
        }
    }

    /** Force-removes a node from the Swarm. Must be drained first. */
    public void forceRemove(String nodeId) {
        log.info("Force-removing Swarm node: {}", nodeId);
        try {
            docker().removeSwarmNodeCmd(nodeId).withForce(true).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to remove node " + nodeId, e);
        }
    }
}
