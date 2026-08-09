package io.rigger.operator.autoscaler;

/**
 * Abstraction over the metrics backend used by the HPA controller.
 *
 * <p>Phase 4 will provide two implementations:
 * <ul>
 *   <li>{@code PrometheusMetricsSource} — queries Prometheus HTTP API</li>
 *   <li>{@code DockerStatsMetricsSource} — uses Docker API task stats (no Prometheus needed)</li>
 * </ul>
 *
 * <p>This interface is a stub that returns 0 until Phase 4 is implemented.
 * Tests inject a mock implementation.
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

    /** Stub implementation — returns 0 until Prometheus integration is wired. */
    MetricsSource STUB = (ns, name) -> 0;
}
