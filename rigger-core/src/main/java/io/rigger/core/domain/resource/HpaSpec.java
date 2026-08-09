package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Horizontal Pod Autoscaler spec embedded in a DeploymentSpec.
 * The Rigger HPA controller reads Prometheus metrics and adjusts replicas.
 *
 * @param minReplicas                    Minimum number of replicas (floor).
 * @param maxReplicas                    Maximum number of replicas (ceiling).
 * @param targetCPUUtilizationPercentage Scale up when average CPU exceeds this %.
 * @param targetMemoryUtilizationPercent Scale up when average memory exceeds this %.
 * @param scaleDownCooldownSeconds       Minimum seconds between scale-down events.
 */
public record HpaSpec(
        @JsonProperty("minReplicas") int minReplicas,
        @JsonProperty("maxReplicas") int maxReplicas,
        @JsonProperty("targetCPUUtilizationPercentage") int targetCPUUtilizationPercentage,
        @JsonProperty("targetMemoryUtilizationPercent") int targetMemoryUtilizationPercent,
        @JsonProperty("scaleDownCooldownSeconds") int scaleDownCooldownSeconds
) {
    public HpaSpec {
        if (minReplicas < 1) throw new IllegalArgumentException("HPA minReplicas must be >= 1");
        if (maxReplicas < minReplicas) throw new IllegalArgumentException("HPA maxReplicas must be >= minReplicas");
        if (scaleDownCooldownSeconds <= 0) scaleDownCooldownSeconds = 180;
    }
}
