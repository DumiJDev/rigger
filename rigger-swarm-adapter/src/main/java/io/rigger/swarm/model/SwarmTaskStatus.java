package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmTaskStatus(
        @JsonProperty("State")   String state,
        @JsonProperty("Message") String message,
        @JsonProperty("Err")     String err
) {
    public boolean isRunning()  { return "running".equals(state); }
    public boolean isFailed()   { return "failed".equals(state) || "rejected".equals(state); }
    public boolean isComplete() { return "complete".equals(state); }
}
