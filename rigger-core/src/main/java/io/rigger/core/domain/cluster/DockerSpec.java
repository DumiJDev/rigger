package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

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
    private static final Set<String> VALID_CHANNELS = Set.of("stable", "test", "nightly");

    public static final DockerSpec DEFAULT = new DockerSpec("26.1", "stable");

    public DockerSpec {
        if (version == null || version.isBlank()) version = "26.1";
        if (channel == null || channel.isBlank()) channel = "stable";
        // This value gets interpolated into a shell command during provisioning
        // (DockerInstaller) — reject anything but the known Docker repo channels.
        if (!VALID_CHANNELS.contains(channel)) {
            throw new IllegalArgumentException(
                "Invalid Docker channel '" + channel + "' — must be one of " + VALID_CHANNELS);
        }
    }
}
