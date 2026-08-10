package io.rigger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.TaskState;
import io.rigger.api.dto.ClusterMetricsResponse;
import io.rigger.api.dto.DeploymentMetricsResponse;
import io.rigger.core.domain.cluster.NodeStatus;
import io.rigger.core.domain.resource.DeploymentSpec;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.exception.ResourceNotFoundException;
import io.rigger.core.domain.resource.ResourceKind;
import io.rigger.operator.autoscaler.MetricsSource;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.NodeRepository;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Point-in-time metrics for the console. Everything here is sampled per request — Rigger stores no
 * time series, so charts come from the console polling and keeping its own window. Deliberately
 * kept cheap: the per-Deployment CPU read costs one Docker stats call per running task.
 */
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

    private final ResourceRepository store;
    private final NodeRepository     nodeRepo;
    private final ServiceAdapter     swarm;
    private final MetricsSource      metrics;
    private final RbacPolicyEngine   rbac;
    private final ObjectMapper       mapper = new ObjectMapper();

    public MetricsController(ResourceRepository store, NodeRepository nodeRepo,
                             ServiceAdapter swarm, MetricsSource metrics, RbacPolicyEngine rbac) {
        this.store = store; this.nodeRepo = nodeRepo;
        this.swarm = swarm; this.metrics = metrics; this.rbac = rbac;
    }

    @GetMapping("/namespaces/{namespace}/deployments/{name}/metrics")
    public ResponseEntity<DeploymentMetricsResponse> deploymentMetrics(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {

        var ctx = ctx(req, namespace);
        rbac.authorize(ctx, "get", "Deployment");

        var entity = store.findByKindAndNamespaceAndName("Deployment", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT, namespace, name));

        DeploymentSpec spec;
        try {
            spec = mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored spec for " + namespace + "/" + name + " is unreadable", e);
        }

        int running = 0;
        var svc = swarm.find(namespace, name);
        if (svc.isPresent()) {
            running = (int) swarm.listTasks(svc.get().getId()).stream()
                .filter(t -> t.getStatus() != null && t.getStatus().getState() == TaskState.RUNNING)
                .count();
        }

        double cpu = 0;
        try {
            cpu = metrics.averageCpuPercent(namespace, name);
        } catch (Exception e) {
            // A metrics hiccup must not turn a dashboard refresh into a 500 — report 0 and move on.
            log.debug("CPU metrics unavailable for {}/{}: {}", namespace, name, e.getMessage());
        }

        var hpa = spec.hpa();
        return ResponseEntity.ok(new DeploymentMetricsResponse(
            namespace, name, cpu, spec.replicas(), running,
            hpa != null,
            hpa != null ? hpa.minReplicas() : null,
            hpa != null ? hpa.maxReplicas() : null,
            hpa != null ? hpa.targetCPUUtilizationPercentage() : null));
    }

    /**
     * Cluster-wide totals. Cluster-admin only, consistent with the other {@code /cluster}
     * endpoints: a namespace-scoped identity has no business enumerating cluster-level topology.
     */
    @GetMapping("/cluster/metrics")
    public ResponseEntity<ClusterMetricsResponse> clusterMetrics(HttpServletRequest req) {
        var ctx = ctx(req, "cluster");
        rbac.authorize(ctx, "get", "Cluster");

        long active = nodeRepo.findByStatus(NodeStatus.ACTIVE).size();
        long total  = nodeRepo.count();

        int managedServices = 0, running = 0, desired = 0;
        try {
            var services = swarm.listManaged();
            managedServices = services.size();
            for (var s : services) {
                if (s.getSpec() != null && s.getSpec().getMode() != null
                        && s.getSpec().getMode().getReplicated() != null) {
                    desired += (int) s.getSpec().getMode().getReplicated().getReplicas();
                }
                running += (int) swarm.listTasks(s.getId()).stream()
                    .filter(t -> t.getStatus() != null && t.getStatus().getState() == TaskState.RUNNING)
                    .count();
            }
        } catch (Exception e) {
            log.debug("Swarm totals unavailable: {}", e.getMessage());
        }

        return ResponseEntity.ok(new ClusterMetricsResponse(
            active, total, managedServices, running, desired,
            store.findAllByKind("Deployment").size(),
            store.findAllByKind("Service").size(),
            store.findAllByKind("ConfigMap").size(),
            store.findAllByKind("Secret").size()));
    }

    private RiggerContext ctx(HttpServletRequest req, String namespace) {
        var existing = (RiggerContext) req.getAttribute("riggerContext");
        if (existing == null) throw new IllegalStateException("No RiggerContext on request");
        return new RiggerContext(existing.identity(), namespace, existing.sourceIp(), existing.timestamp());
    }
}
