package io.rigger.operator.metrics;

import java.util.Set;

/**
 * The metric names {@link MetricsSampler} writes and the series endpoint accepts.
 *
 * <p>Enumerated rather than free-form so a typo in a console query returns a clear 422 instead of
 * an empty array that looks like "no data yet" — the two are indistinguishable to whoever is
 * staring at a flat chart.
 */
public final class MetricNames {

    /** Namespace and name used for cluster-wide series, so every series has a full triple. */
    public static final String CLUSTER_SCOPE = "cluster";

    // Cluster-wide.
    public static final String NODES_ACTIVE          = "nodes.active";
    public static final String NODES_TOTAL           = "nodes.total";
    public static final String SERVICES_MANAGED      = "services.managed";
    public static final String REPLICAS_RUNNING      = "replicas.running";
    public static final String REPLICAS_DESIRED      = "replicas.desired";
    public static final String RESOURCES_DEPLOYMENTS = "resources.deployments";
    public static final String RESOURCES_SERVICES    = "resources.services";
    public static final String RESOURCES_CONFIGMAPS  = "resources.configmaps";
    public static final String RESOURCES_SECRETS     = "resources.secrets";

    // Per-Deployment, keyed by the Deployment's own namespace and name.
    public static final String DEPLOYMENT_CPU               = "deployment.cpu";
    public static final String DEPLOYMENT_REPLICAS_RUNNING  = "deployment.replicas.running";
    public static final String DEPLOYMENT_REPLICAS_DESIRED  = "deployment.replicas.desired";

    public static final Set<String> CLUSTER_METRICS = Set.of(
        NODES_ACTIVE, NODES_TOTAL, SERVICES_MANAGED, REPLICAS_RUNNING, REPLICAS_DESIRED,
        RESOURCES_DEPLOYMENTS, RESOURCES_SERVICES, RESOURCES_CONFIGMAPS, RESOURCES_SECRETS);

    public static final Set<String> DEPLOYMENT_METRICS = Set.of(
        DEPLOYMENT_CPU, DEPLOYMENT_REPLICAS_RUNNING, DEPLOYMENT_REPLICAS_DESIRED);

    public static boolean isKnown(String metric) {
        return CLUSTER_METRICS.contains(metric) || DEPLOYMENT_METRICS.contains(metric);
    }

    /** Cluster metrics are cluster-scoped and so require cluster-admin; Deployment metrics do not. */
    public static boolean isClusterScoped(String metric) {
        return CLUSTER_METRICS.contains(metric);
    }

    private MetricNames() { }
}
