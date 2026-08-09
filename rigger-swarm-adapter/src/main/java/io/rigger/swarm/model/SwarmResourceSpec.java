package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** CPU (nanoCPUs) and memory (bytes) limits or reservations. */
public record SwarmResourceSpec(
        @JsonProperty("NanoCPUs")    long nanoCpus,
        @JsonProperty("MemoryBytes") long memoryBytes
) {
    /** Converts a CPU fraction (e.g. 0.5) to Docker nanoCPUs (0.5 * 1e9 = 500000000). */
    public static long cpuToNano(double cpuFraction) {
        return (long)(cpuFraction * 1_000_000_000L);
    }
}
