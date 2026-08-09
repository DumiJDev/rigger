package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CPU and memory limits for a container.
 * Format matches Docker Swarm resource spec.
 *
 * @param cpuLimit    CPU limit as a fraction of a core (e.g. "0.5" = 500m).
 * @param memoryLimit Memory limit in bytes or human-readable (e.g. "512Mi").
 * @param cpuReserved Reserved CPU (minimum guaranteed).
 * @param memoryReserved Reserved memory.
 */
public record ResourceRequirements(
        @JsonProperty("cpuLimit") String cpuLimit,
        @JsonProperty("memoryLimit") String memoryLimit,
        @JsonProperty("cpuReserved") String cpuReserved,
        @JsonProperty("memoryReserved") String memoryReserved
) {}
