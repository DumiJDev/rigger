package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Point-in-time metrics for one Deployment. Sampled on request — nothing is stored as a time
 * series, so charts are built by the console polling this and keeping its own window.
 */
public record DeploymentMetricsResponse(
        @JsonProperty("namespace")        String namespace,
        @JsonProperty("name")             String name,
        @JsonProperty("cpuPercent")       double cpuPercent,
        @JsonProperty("desiredReplicas")  int desiredReplicas,
        @JsonProperty("runningReplicas")  int runningReplicas,
        @JsonProperty("hpaEnabled")       boolean hpaEnabled,
        @JsonProperty("hpaMinReplicas")   Integer hpaMinReplicas,
        @JsonProperty("hpaMaxReplicas")   Integer hpaMaxReplicas,
        @JsonProperty("hpaTargetCpu")     Integer hpaTargetCpu
) {}
