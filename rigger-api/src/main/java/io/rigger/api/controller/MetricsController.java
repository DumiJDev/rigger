package io.rigger.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.api.dto.ClusterMetricsResponse;
import io.rigger.api.dto.DeploymentMetricsResponse;
import io.rigger.api.dto.MetricSeriesResponse;
import io.rigger.core.domain.resource.DeploymentSpec;
import io.rigger.core.domain.resource.ResourceKind;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.exception.InvalidRequestException;
import io.rigger.core.exception.ResourceNotFoundException;
import io.rigger.operator.metrics.MetricNames;
import io.rigger.operator.metrics.MetricsCollector;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.MetricSampleRepository;
import io.rigger.store.repository.ResourceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Metrics for the console: current values, plus the history {@code MetricsSampler} records.
 *
 * <p>The current-value endpoints delegate to {@link MetricsCollector}, the same component the
 * sampler uses — computing the totals here as well is how the number and the chart above it end up
 * disagreeing.
 */
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    /** A day, matching the sampler's default retention — asking for more returns what exists. */
    private static final Duration MAX_WINDOW     = Duration.ofHours(24);
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

    private final ResourceRepository     store;
    private final MetricSampleRepository samples;
    private final MetricsCollector       collector;
    private final RbacPolicyEngine       rbac;
    private final ObjectMapper           mapper = new ObjectMapper();

    public MetricsController(ResourceRepository store, MetricSampleRepository samples,
                             MetricsCollector collector, RbacPolicyEngine rbac) {
        this.store = store; this.samples = samples;
        this.collector = collector; this.rbac = rbac;
    }

    @GetMapping("/namespaces/{namespace}/deployments/{name}/metrics")
    public ResponseEntity<DeploymentMetricsResponse> deploymentMetrics(
            @PathVariable String namespace, @PathVariable String name, HttpServletRequest req) {

        rbac.authorize(ctx(req, namespace), "get", "Deployment");

        var spec = deploymentSpec(namespace, name);
        var d    = collector.deployment(namespace, name, spec.replicas());
        var hpa  = spec.hpa();

        return ResponseEntity.ok(new DeploymentMetricsResponse(
            namespace, name, d.cpuPercent(), d.desiredReplicas(), d.runningReplicas(),
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
        rbac.authorize(ctx(req, MetricNames.CLUSTER_SCOPE), "get", "Cluster");

        var c = collector.cluster();
        return ResponseEntity.ok(new ClusterMetricsResponse(
            c.activeNodes(), c.totalNodes(), c.managedServices(),
            c.runningReplicas(), c.desiredReplicas(),
            c.deployments(), c.services(), c.configMaps(), c.secrets()));
    }

    /**
     * One metric's history, oldest point first.
     *
     * <p>Cluster metrics need no {@code namespace}/{@code name} and require cluster-admin;
     * Deployment metrics require both and are authorized against the namespace, so a scoped
     * identity can chart its own workloads but not the cluster's.
     */
    @GetMapping("/metrics/series")
    public ResponseEntity<MetricSeriesResponse> series(
            @RequestParam String metric,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer minutes,
            HttpServletRequest req) {

        if (!MetricNames.isKnown(metric)) {
            throw new InvalidRequestException("Unknown metric '" + metric + "'");
        }

        String ns, resource;
        if (MetricNames.isClusterScoped(metric)) {
            ns = resource = MetricNames.CLUSTER_SCOPE;
            rbac.authorize(ctx(req, MetricNames.CLUSTER_SCOPE), "get", "Cluster");
        } else {
            if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
                throw new InvalidRequestException(
                    "Metric '" + metric + "' is per-Deployment and requires namespace and name");
            }
            ns = namespace;
            resource = name;
            rbac.authorize(ctx(req, namespace), "get", "Deployment");
        }

        var since  = Instant.now().minus(window(minutes));
        var points = samples.series(metric, ns, resource, since).stream()
            .map(s -> new MetricSeriesResponse.Point(s.getSampledAt(), s.getValue()))
            .toList();

        return ResponseEntity.ok(new MetricSeriesResponse(metric, ns, resource, points));
    }

    /**
     * Names with recorded samples for a per-Deployment metric, so a chart can plot every series in
     * a namespace without first listing Deployments and discovering half have no history.
     */
    @GetMapping("/namespaces/{namespace}/metrics/series-names")
    public ResponseEntity<List<String>> seriesNames(
            @PathVariable String namespace,
            @RequestParam String metric,
            @RequestParam(required = false) Integer minutes,
            HttpServletRequest req) {

        rbac.authorize(ctx(req, namespace), "get", "Deployment");

        if (!MetricNames.DEPLOYMENT_METRICS.contains(metric)) {
            throw new InvalidRequestException(
                "Metric '" + metric + "' is not per-Deployment and has no series names");
        }
        return ResponseEntity.ok(
            samples.namesFor(metric, namespace, Instant.now().minus(window(minutes))));
    }

    /** Clamped rather than rejected: a window wider than retention is a reasonable ask, it just has no more data. */
    private static Duration window(Integer minutes) {
        if (minutes == null) return DEFAULT_WINDOW;
        if (minutes <= 0) throw new InvalidRequestException("minutes must be positive");
        var requested = Duration.ofMinutes(minutes);
        return requested.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : requested;
    }

    private DeploymentSpec deploymentSpec(String namespace, String name) {
        var entity = store.findByKindAndNamespaceAndName("Deployment", namespace, name)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT, namespace, name));
        try {
            return mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored spec for " + namespace + "/" + name + " is unreadable", e);
        }
    }

    private RiggerContext ctx(HttpServletRequest req, String namespace) {
        var existing = (RiggerContext) req.getAttribute("riggerContext");
        if (existing == null) throw new IllegalStateException("No RiggerContext on request");
        return new RiggerContext(existing.identity(), namespace, existing.sourceIp(), existing.timestamp());
    }
}
