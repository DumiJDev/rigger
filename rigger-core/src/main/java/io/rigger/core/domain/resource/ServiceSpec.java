package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Spec for a Rigger Service resource.
 * Maps to a Docker overlay network virtual IP (ClusterIP)
 * or published port plus Traefik routing labels (LoadBalancer).
 *
 * @param selector    Labels to match the target Deployment pods.
 * @param ports       Port mappings.
 * @param type        ClusterIP | LoadBalancer.
 * @param ingress     Optional HTTP ingress (host/path/TLS) routed by the cluster's Traefik
 *                    controller. Only honoured for {@code LoadBalancer}.
 */
public record ServiceSpec(
        @JsonProperty("selector") Map<String, String> selector,
        @JsonProperty("ports") List<ServicePort> ports,
        @JsonProperty("type") ServiceType type,
        @JsonProperty("ingress") IngressSpec ingress
) {
    public ServiceSpec {
        if (selector == null || selector.isEmpty())
            throw new IllegalArgumentException("Service selector must not be empty");
        if (ports == null || ports.isEmpty())
            throw new IllegalArgumentException("Service must declare at least one port");
        if (type == null) type = ServiceType.CLUSTER_IP;
    }

    /**
     * Three-argument construction path, kept explicitly for callers that build a Service with no
     * ingress — notably {@code ComposeConverter}, which converts docker-compose input and has no
     * ingress concept. Do not remove: adding {@code ingress} as a record component would otherwise
     * have been a source-breaking change to every existing construction site.
     */
    public ServiceSpec(Map<String, String> selector, List<ServicePort> ports, ServiceType type) {
        this(selector, ports, type, null);
    }
}
