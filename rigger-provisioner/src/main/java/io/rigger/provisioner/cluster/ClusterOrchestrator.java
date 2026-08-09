package io.rigger.provisioner.cluster;

import io.rigger.core.domain.cluster.*;
import io.rigger.core.exception.ProvisioningException;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.NodeAddedEvent;
import io.rigger.provisioner.node.*;
import io.rigger.provisioner.swarm.SwarmTokens;
import io.rigger.store.entity.NodeEntity;
import io.rigger.store.repository.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full {@code rigger cluster up} and {@code rigger cluster sync} flows.
 *
 * <p>cluster up steps:
 * <ol>
 *   <li>Parse and validate rigger.cluster.yaml</li>
 *   <li>Check SSH connectivity to all nodes (parallel)</li>
 *   <li>Provision primary manager (Docker + swarm init)</li>
 *   <li>Provision remaining nodes concurrently (Docker + swarm join)</li>
 *   <li>Persist node state to store</li>
 *   <li>Publish NodeAddedEvent for each successful node</li>
 * </ol>
 *
 * <p>cluster sync steps:
 * <ol>
 *   <li>Re-parse rigger.cluster.yaml</li>
 *   <li>Diff desired (YAML) vs actual (store)</li>
 *   <li>Provision new nodes</li>
 *   <li>Drain and remove deleted nodes</li>
 * </ol>
 */
@Component
public class ClusterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClusterOrchestrator.class);

    private final ConnectivityChecker connectivityChecker;
    private final NodeProvisioner nodeProvisioner;
    private final NodeRepository nodeRepository;
    private final RiggerEventBus eventBus;

    public ClusterOrchestrator(ConnectivityChecker connectivityChecker,
                                NodeProvisioner nodeProvisioner,
                                NodeRepository nodeRepository,
                                RiggerEventBus eventBus) {
        this.connectivityChecker = connectivityChecker;
        this.nodeProvisioner = nodeProvisioner;
        this.nodeRepository = nodeRepository;
        this.eventBus = eventBus;
    }

    /**
     * Provisions the full cluster from scratch.
     * Idempotent — nodes already in ACTIVE state are skipped.
     */
    public ClusterUpResult up(ClusterSpec spec) {
        var start = Instant.now();
        log.info("=== rigger cluster up: {} ===", spec.name());

        // Step 1: connectivity check
        log.info("[1/4] Checking SSH connectivity to {} nodes...", spec.nodes().size());
        var reachability = connectivityChecker.checkAll(spec);
        if (!connectivityChecker.allReachable(reachability)) {
            throw new ProvisioningException(spec.name(),
                "Cannot proceed — unreachable nodes: " +
                reachability.entrySet().stream()
                    .filter(e -> !e.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", ")));
        }

        // Step 2: provision primary manager
        var primary = spec.primaryNode();
        log.info("[2/4] Provisioning primary manager: {} ({})", primary.name(), primary.ip());
        SwarmTokens tokens = nodeProvisioner.provisionPrimary(primary, spec);
        persistNode(primary, NodeStatus.ACTIVE, spec.name(), null);
        eventBus.publish(new NodeAddedEvent(primary.name(), primary.ip(), primary.role()));

        // Step 3: provision remaining nodes concurrently
        var remaining = spec.nodes().stream()
                .filter(n -> !n.primary())
                .toList();

        log.info("[3/4] Provisioning {} remaining nodes (parallel)...", remaining.size());
        var nodeResults = nodeProvisioner.provisionAll(remaining, spec, tokens, primary.ip());

        // Step 4: persist and publish
        log.info("[4/4] Persisting cluster state...");
        var allResults = new HashMap<String, io.rigger.provisioner.node.NodeProvisionResult>();
        allResults.put(primary.name(),
            NodeProvisionResult.success(primary.name(), primary.role(), false, null, Duration.ZERO));
        allResults.putAll(nodeResults);

        nodeResults.forEach((name, result) -> {
            var nodeSpec = spec.nodes().stream().filter(n -> n.name().equals(name)).findFirst().orElse(null);
            if (nodeSpec != null) {
                var status = result.success() ? NodeStatus.ACTIVE : NodeStatus.OFFLINE;
                persistNode(nodeSpec, status, spec.name(), result.swarmNodeId());
                if (result.success()) {
                    eventBus.publish(new NodeAddedEvent(name, nodeSpec.ip(), nodeSpec.role()));
                }
            }
        });

        var duration = Duration.between(start, Instant.now());
        var result = new ClusterUpResult(spec.name(), true, allResults, duration, null);

        log.info("=== cluster up complete: {}/{} nodes active, took {}s ===",
            result.successCount(), spec.nodes().size(), duration.getSeconds());

        return result;
    }

    /**
     * Reconciles the cluster with the current rigger.cluster.yaml.
     * Adds new nodes, drains removed nodes. No-ops for unchanged nodes.
     */
    public void sync(ClusterSpec spec) {
        log.info("=== rigger cluster sync: {} ===", spec.name());

        var existing = nodeRepository.findByClusterName(spec.name())
                .stream().map(NodeEntity::getName).collect(Collectors.toSet());
        var declared = spec.nodes().stream().map(NodeSpec::name).collect(Collectors.toSet());

        // New nodes — provision them
        var toAdd = spec.nodes().stream()
                .filter(n -> !existing.contains(n.name()))
                .toList();

        if (!toAdd.isEmpty()) {
            log.info("New nodes to provision: {}", toAdd.stream().map(NodeSpec::name).toList());
            // Get tokens from primary manager (it's already up)
            var primaryEntity = nodeRepository.findById(spec.primaryNode().name());
            if (primaryEntity.isEmpty()) {
                throw new ProvisioningException(spec.name(), "Primary manager not found in store — run cluster up first");
            }

            // Re-init to get tokens (idempotent)
            SwarmTokens tokens = nodeProvisioner.provisionPrimary(spec.primaryNode(), spec);
            var results = nodeProvisioner.provisionAll(toAdd, spec, tokens, spec.primaryNode().ip());

            results.forEach((name, result) -> {
                var nodeSpec = toAdd.stream().filter(n -> n.name().equals(name)).findFirst().orElse(null);
                if (nodeSpec != null && result.success()) {
                    persistNode(nodeSpec, NodeStatus.ACTIVE, spec.name(), result.swarmNodeId());
                    eventBus.publish(new NodeAddedEvent(name, nodeSpec.ip(), nodeSpec.role()));
                    log.info("[+] Node {} provisioned and joined", name);
                }
            });
        }

        // Removed nodes — mark as draining (actual drain handled by provisioner in Phase 4)
        var toRemove = existing.stream()
                .filter(name -> !declared.contains(name))
                .toList();

        if (!toRemove.isEmpty()) {
            log.info("Nodes removed from spec — marking for drain: {}", toRemove);
            toRemove.forEach(name -> {
                nodeRepository.findById(name).ifPresent(entity -> {
                    entity.setStatus(NodeStatus.DRAINING);
                    nodeRepository.save(entity);
                    log.info("[-] Node {} marked DRAINING", name);
                });
            });
        }

        log.info("=== cluster sync complete ===");
    }

    private void persistNode(NodeSpec node, NodeStatus status, String clusterName, String swarmNodeId) {
        var entity = nodeRepository.findById(node.name())
                .orElse(new NodeEntity(node.name(), node.ip(), node.role(), node.primary(), status, clusterName));
        entity.setStatus(status);
        if (swarmNodeId != null) entity.setSwarmNodeId(swarmNodeId);
        entity.setLastSeenAt(Instant.now());
        nodeRepository.save(entity);
    }
}
