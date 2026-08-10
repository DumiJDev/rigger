package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Cluster-wide totals for the console dashboard. Sampled on request, not stored. */
public record ClusterMetricsResponse(
        @JsonProperty("activeNodes")      long activeNodes,
        @JsonProperty("totalNodes")       long totalNodes,
        @JsonProperty("managedServices")  int  managedServices,
        @JsonProperty("runningTasks")     int  runningTasks,
        @JsonProperty("desiredTasks")     int  desiredTasks,
        @JsonProperty("deployments")      long deployments,
        @JsonProperty("services")         long services,
        @JsonProperty("configMaps")       long configMaps,
        @JsonProperty("secrets")          long secrets
) {}
