package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Rolling update configuration. Maps from RollingUpdateStrategy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmUpdateConfig(
        @JsonProperty("Parallelism")   long parallelism,
        @JsonProperty("Delay")         long delayNanos,
        @JsonProperty("FailureAction") String failureAction,
        @JsonProperty("Order")         String order
) {
    /** Converts delaySeconds (Rigger) to nanoseconds (Docker API). */
    public static SwarmUpdateConfig from(int parallelism, int delaySeconds, String failureAction) {
        return new SwarmUpdateConfig(parallelism, (long) delaySeconds * 1_000_000_000L,
                failureAction.toLowerCase(), "stop-first");
    }
}
