package io.rigger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.TaskState;
import io.rigger.api.dto.TopologyResponse;
import io.rigger.api.stream.NamespaceSseHub;
import io.rigger.core.domain.resource.DeploymentSpec;
import io.rigger.core.domain.resource.ServiceSpec;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.exception.AccessDeniedException;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

/**
 * Builds the namespace workload graph that backs the console's topology view.
 *
 * <p>Derived entirely from data that already exists (stored specs + live Swarm task state) — no
 * new schema. Doing the selector matching and reference resolution here rather than in the browser
 * keeps one implementation of "what is connected to what", shared with how the operator actually
 * wires things up.
 */
@RestController
@RequestMapping("/api/v1/namespaces/{namespace}")
public class TopologyController {

    private static final Logger log = LoggerFactory.getLogger(TopologyController.class);

    private final ResourceRepository store;
    private final ServiceAdapter     swarm;
    private final RbacPolicyEngine   rbac;
    private final NamespaceSseHub    sseHub;
    private final ObjectMapper       mapper = new ObjectMapper();

    public TopologyController(ResourceRepository store, ServiceAdapter swarm, RbacPolicyEngine rbac,
                               NamespaceSseHub sseHub) {
        this.store = store; this.swarm = swarm; this.rbac = rbac; this.sseHub = sseHub;
    }

    @GetMapping("/topology")
    public ResponseEntity<TopologyResponse> topology(
            @PathVariable String namespace, HttpServletRequest req) {

        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Deployment");

        var nodes = new ArrayList<TopologyResponse.Node>();
        var edges = new ArrayList<TopologyResponse.Edge>();

        var deployments = store.findByKindAndNamespace("Deployment", namespace);
        var deploymentSpecs = new LinkedHashMap<String, DeploymentSpec>();

        for (var entity : deployments) {
            var spec = parse(entity, DeploymentSpec.class);
            if (spec == null) continue;
            deploymentSpecs.put(entity.getName(), spec);

            int running = runningReplicas(namespace, entity.getName());
            boolean onSwarm = swarm.find(namespace, entity.getName()).isPresent();

            nodes.add(new TopologyResponse.Node(
                nodeId("Deployment", entity.getName()), "Deployment", entity.getName(),
                spec.image(), spec.replicas(), running,
                health(spec.replicas(), running, onSwarm), spec.hpa() != null));
        }

        // Services attach to whichever Deployment their selector matches — same rule
        // ServiceController uses when it decides where to publish ports.
        for (var entity : store.findByKindAndNamespace("Service", namespace)) {
            var spec = parse(entity, ServiceSpec.class);
            if (spec == null) continue;
            String id = nodeId("Service", entity.getName());
            nodes.add(new TopologyResponse.Node(
                id, "Service", entity.getName(), null, null, null, "n/a", false));

            deploymentSpecs.forEach((depName, depSpec) -> {
                if (depSpec.selector() != null
                        && depSpec.selector().entrySet().containsAll(spec.selector().entrySet())) {
                    edges.add(new TopologyResponse.Edge(id, nodeId("Deployment", depName), "exposes"));
                }
            });
        }

        // ConfigMaps/Secrets only need their names to be placed and linked — their contents are
        // irrelevant here, and Secret values must never be read on a read-only view anyway.
        addMountedNodes(namespace, "ConfigMap", deploymentSpecs, DeploymentSpec::configMapRefs, nodes, edges);
        addMountedNodes(namespace, "Secret",    deploymentSpecs, DeploymentSpec::secretRefs,    nodes, edges);

        return ResponseEntity.ok(new TopologyResponse(namespace, nodes, edges));
    }

    /**
     * Pushes a ping (no payload beyond the event type) whenever a resource in this namespace is
     * applied, deleted or scaled, so the console can refetch {@link #topology} instead of waiting
     * on its polling interval. See {@link NamespaceSseHub} for why this carries no diff/state.
     *
     * <p>A denied {@link AccessDeniedException} is caught here rather than left to
     * {@code GlobalExceptionHandler}: its JSON error body can't be negotiated against a request
     * whose {@code Accept} is {@code text/event-stream} only, which turned a legitimate 403 into a
     * 500 — found by actually opening this stream as a namespace-scoped VIEWER against a foreign
     * namespace, not by compiling or unit-testing it.
     */
    @GetMapping("/topology/stream")
    public ResponseEntity<SseEmitter> topologyStream(
            @PathVariable String namespace, HttpServletRequest req) {
        var ctx = ctx(req, namespace);
        try {
            rbac.authorize(ctx, "get", "Deployment");
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(sseHub.subscribeTopology(namespace));
    }

    private void addMountedNodes(String namespace, String kind,
                                 Map<String, DeploymentSpec> deployments,
                                 java.util.function.Function<DeploymentSpec, List<String>> refs,
                                 List<TopologyResponse.Node> nodes, List<TopologyResponse.Edge> edges) {
        for (var entity : store.findByKindAndNamespace(kind, namespace)) {
            String id = nodeId(kind, entity.getName());
            nodes.add(new TopologyResponse.Node(
                id, kind, entity.getName(), null, null, null, "n/a", false));

            deployments.forEach((depName, depSpec) -> {
                var referenced = refs.apply(depSpec);
                if (referenced != null && referenced.contains(entity.getName())) {
                    edges.add(new TopologyResponse.Edge(nodeId("Deployment", depName), id, "mounts"));
                }
            });
        }
    }

    private int runningReplicas(String namespace, String name) {
        try {
            var svc = swarm.find(namespace, name);
            if (svc.isEmpty()) return 0;
            return (int) swarm.listTasks(svc.get().getId()).stream()
                .filter(t -> t.getStatus() != null && t.getStatus().getState() == TaskState.RUNNING)
                .count();
        } catch (Exception e) {
            log.debug("Could not read task state for {}/{}: {}", namespace, name, e.getMessage());
            return 0;
        }
    }

    private String health(int desired, int running, boolean onSwarm) {
        if (!onSwarm) return "unknown";
        if (desired == 0)    return "healthy";   // scaled to zero on purpose
        if (running >= desired) return "healthy";
        return running == 0 ? "down" : "degraded";
    }

    private <T> T parse(ResourceEntity entity, Class<T> type) {
        try {
            return mapper.readValue(entity.getSpecJson(), type);
        } catch (Exception e) {
            log.debug("Skipping unreadable {} spec {}/{}", entity.getKind(), entity.getNamespace(), entity.getName());
            return null;
        }
    }

    private String nodeId(String kind, String name) {
        return kind + "/" + name;
    }

    private RiggerContext ctx(HttpServletRequest req, String namespace) {
        var existing = (RiggerContext) req.getAttribute("riggerContext");
        if (existing == null) throw new IllegalStateException("No RiggerContext on request");
        return new RiggerContext(existing.identity(), namespace, existing.sourceIp(), existing.timestamp());
    }
}
