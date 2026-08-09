package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Port mapping for a Rigger Service.
 *
 * @param port       Port exposed on the service (internal VIP).
 * @param targetPort Port the container is listening on.
 * @param protocol   TCP | UDP (default TCP).
 */
public record ServicePort(
        @JsonProperty("port") int port,
        @JsonProperty("targetPort") int targetPort,
        @JsonProperty("protocol") String protocol
) {
    public ServicePort {
        if (protocol == null) protocol = "TCP";
    }
}
