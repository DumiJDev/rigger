package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mount target for a Docker Config inside the container filesystem. */
public record SwarmConfigFile(
        @JsonProperty("Name") String name,
        @JsonProperty("UID")  String uid,
        @JsonProperty("GID")  String gid,
        @JsonProperty("Mode") int mode
) {
    public static SwarmConfigFile defaults(String name) {
        return new SwarmConfigFile(name, "0", "0", 0444);
    }
}
