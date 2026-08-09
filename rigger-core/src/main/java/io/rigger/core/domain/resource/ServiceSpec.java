package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Spec for a Rigger Service resource.
 * Maps to a Docker overlay network virtual IP (ClusterIP)
 * or published port with Traefik label (LoadBalancer).
 *
 * @param selector    Labels to match the target Deployment pods.
 * @param ports       Port mappings.
 * @param type        ClusterIP | LoadBalancer.
 */
public record ServiceSpec(
        @JsonProperty("selector") Map<String, String> selector,
        @JsonProperty("ports") List<ServicePort> ports,
        @JsonProperty("type") ServiceType type
) {
    public ServiceSpec {
        if (selector == null || selector.isEmpty())
            throw new IllegalArgumentException("Service selector must not be empty");
        if (ports == null || ports.isEmpty())
            throw new IllegalArgumentException("Service must declare at least one port");
        if (type == null) type = ServiceType.CLUSTER_IP;
    }
}
