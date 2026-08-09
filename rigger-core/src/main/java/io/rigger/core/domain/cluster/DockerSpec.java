package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Docker installation spec applied to nodes that don't have Docker.
 *
 * @param version Docker Engine version to install (e.g. "26.1").
 * @param channel Release channel: stable | test | nightly.
 */
public record DockerSpec(
        @JsonProperty("version") String version,
        @JsonProperty("channel") String channel
) {
    public static final DockerSpec DEFAULT = new DockerSpec("26.1", "stable");

    public DockerSpec {
        if (version == null || version.isBlank()) version = "26.1";
        if (channel == null || channel.isBlank()) channel = "stable";
    }
}
