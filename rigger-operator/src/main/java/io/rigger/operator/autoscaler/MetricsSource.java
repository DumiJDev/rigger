package io.rigger.operator.autoscaler;

/**
 * Abstraction over the metrics backend, used by the HPA controller and by
 * {@code MetricsSampler} when recording history.
 *
 * <p>{@link DockerStatsMetricsSource} is the shipped implementation — Docker task stats, no
 * Prometheus required. {@link #STUB} exists so tests and Swarm-less runs get zeros instead of
 * failures, and is registered only when nothing else is.
 */
public interface MetricsSource {

    /**
     * Returns the average CPU utilisation percentage across all running pods
     * of a given Deployment.
     *
     * @param namespace  Deployment namespace.
     * @param name       Deployment name.
     * @return CPU utilisation as a percentage (0-100+). 0 if unavailable.
     */
    double averageCpuPercent(String namespace, String name);

    /**
     * Returns the average memory utilisation percentage across all running pods.
     *
     * @param namespace Deployment namespace.
     * @param name      Deployment name.
     * @return Memory utilisation as a percentage (0-100). 0 if unavailable.
     */
    default double averageMemoryPercent(String namespace, String name) { return 0; }

    /** Fallback that reports no load, for tests and for runs with no reachable Swarm. */
    MetricsSource STUB = (ns, name) -> 0;
}
