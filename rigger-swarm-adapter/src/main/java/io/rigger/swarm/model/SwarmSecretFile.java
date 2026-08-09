package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mount target for a Docker Secret inside the container filesystem. */
public record SwarmSecretFile(
        @JsonProperty("Name") String name,
        @JsonProperty("UID")  String uid,
        @JsonProperty("GID")  String gid,
        @JsonProperty("Mode") int mode
) {
    public static SwarmSecretFile defaults(String name) {
        return new SwarmSecretFile(name, "0", "0", 0444);
    }
}
