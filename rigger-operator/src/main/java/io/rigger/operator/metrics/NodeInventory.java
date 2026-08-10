package io.rigger.operator.metrics;

import com.github.dockerjava.api.model.SwarmNode;
import io.rigger.core.domain.cluster.NodeRole;
import io.rigger.core.domain.cluster.NodeStatus;
import io.rigger.store.entity.NodeEntity;
import io.rigger.store.repository.NodeRepository;
import io.rigger.swarm.adapter.NodeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The cluster's nodes, with Swarm as the source of truth.
 *
 * <p>Previously every node read came from the {@code cluster_nodes} table, which only
 * {@code ClusterOrchestrator} — the SSH provisioning path — ever writes. So a Swarm that Rigger
 * attached to instead of provisioning reported <strong>zero nodes</strong> everywhere: the Nodes
 * page, the dashboard's node KPI, and the cluster metrics all said 0/0 while
 * {@code docker node ls} showed a healthy leader. That is the documented development setup
 * ({@code RIGGER_ATTACH_EXISTING_SWARM}) and the one CI uses, so the most common way to run Rigger
 * was the one where this looked broken.
 *
 * <p>Swarm knows the live state — membership, reachability, manager role — and it knows it for every
 * node regardless of how it got there. The store still contributes what only Rigger knows: the SSH
 * address it was provisioned at, and which node the provisioner treats as primary. Rows are matched
 * on Swarm node ID, falling back to hostname for rows written before an ID was recorded.
 */
@Component
public class NodeInventory {

    private static final Logger log = LoggerFactory.getLogger(NodeInventory.class);

    private final NodeAdapter    swarm;
    private final NodeRepository store;

    public NodeInventory(NodeAdapter swarm, NodeRepository store) {
        this.swarm = swarm;
        this.store = store;
    }

    /**
     * Every node Swarm reports, enriched from the store. Sorted managers first then by name, so the
     * list is stable across calls — Swarm's own ordering is not.
     *
     * <p>If Swarm is unreachable this falls back to the stored rows rather than returning nothing:
     * a stale list beats an empty page that implies the cluster has no nodes.
     */
    public List<NodeView> list() {
        List<SwarmNode> nodes;
        try {
            nodes = swarm.listNodes();
        } catch (Exception e) {
            log.debug("Swarm node list unavailable, falling back to stored rows: {}", e.getMessage());
            return store.findAll().stream().map(NodeInventory::fromStore).toList();
        }

        Map<String, NodeEntity> byId = store.findAll().stream()
            .filter(n -> n.getSwarmNodeId() != null)
            .collect(Collectors.toMap(NodeEntity::getSwarmNodeId, Function.identity(), (a, b) -> a));
        Map<String, NodeEntity> byName = store.findAll().stream()
            .collect(Collectors.toMap(NodeEntity::getName, Function.identity(), (a, b) -> a));

        return nodes.stream()
            .map(node -> {
                String hostname = hostname(node);
                NodeEntity stored = byId.get(node.getId());
                if (stored == null) stored = byName.get(hostname);
                return merge(node, hostname, stored);
            })
            .sorted(Comparator.comparing((NodeView v) -> v.role() != NodeRole.MANAGER)
                .thenComparing(NodeView::name))
            .toList();
    }

    /** Count of nodes accepting tasks, and the total. */
    public Counts counts() {
        var all = list();
        long active = all.stream().filter(n -> n.status() == NodeStatus.ACTIVE).count();
        return new Counts(active, all.size());
    }

    private static NodeView merge(SwarmNode node, String hostname, NodeEntity stored) {
        return new NodeView(
            hostname,
            // Swarm reports the address it uses to reach the node; the provisioned SSH address is
            // the better answer when we have it, since that is what an operator would connect to.
            stored != null && stored.getIp() != null ? stored.getIp() : swarmAddress(node),
            role(node),
            status(node),
            stored != null && stored.isPrimary(),
            node.getId(),
            // Swarm has no "last seen" for a node; the meaningful stand-in is when its state last
            // changed, which is what the store tracks for provisioned nodes.
            stored != null ? stored.getLastSeenAt() : null);
    }

    private static NodeView fromStore(NodeEntity n) {
        return new NodeView(n.getName(), n.getIp(), n.getRole(), n.getStatus(),
            n.isPrimary(), n.getSwarmNodeId(), n.getLastSeenAt());
    }

    private static String hostname(SwarmNode node) {
        if (node.getDescription() != null && node.getDescription().getHostname() != null) {
            return node.getDescription().getHostname();
        }
        return node.getId();
    }

    private static String swarmAddress(SwarmNode node) {
        return node.getStatus() != null ? node.getStatus().getAddress() : null;
    }

    private static NodeRole role(SwarmNode node) {
        var spec = node.getSpec();
        return spec != null && spec.getRole() != null
            && "manager".equalsIgnoreCase(spec.getRole().name())
            ? NodeRole.MANAGER : NodeRole.WORKER;
    }

    /**
     * Maps Swarm's two independent axes onto Rigger's single status. Availability is the operator's
     * intent (drained on purpose) and reachability is the fact, so intent is checked first: a node
     * deliberately drained should not read as OFFLINE, which means "we lost it".
     */
    private static NodeStatus status(SwarmNode node) {
        var spec = node.getSpec();
        if (spec != null && spec.getAvailability() != null) {
            String availability = spec.getAvailability().name();
            if ("DRAIN".equalsIgnoreCase(availability)) return NodeStatus.DRAINING;
            if ("PAUSE".equalsIgnoreCase(availability)) return NodeStatus.DRAINING;
        }
        var status = node.getStatus();
        if (status == null || status.getState() == null) return NodeStatus.OFFLINE;
        return "ready".equalsIgnoreCase(status.getState().name())
            ? NodeStatus.ACTIVE : NodeStatus.OFFLINE;
    }

    /** Mirrors {@code NodeResponse} without rigger-api having to depend on this module's shape. */
    public record NodeView(String name, String ip, NodeRole role, NodeStatus status,
                           boolean primary, String swarmNodeId, Instant lastSeenAt) { }

    public record Counts(long active, long total) { }
}
