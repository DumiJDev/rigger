package io.rigger.core.domain.resource;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The single Rigger Service that a Deployment's Swarm service must reflect: its published ports and,
 * when declared, its HTTP ingress.
 *
 * <p><strong>Why this type exists.</strong> Published ports and Traefik labels have to be produced
 * inside {@code ServiceAdapter.buildServiceSpec()}, because that method rebuilds the entire
 * docker-java {@code ServiceSpec} from scratch on every update — anything not produced there is
 * erased on the next Deployment update. But {@code rigger-swarm-adapter} does not depend on
 * {@code rigger-store}, so the adapter cannot look a Service up itself. The operator resolves it
 * (see {@code ServiceBindingResolver}) once per reconciliation cycle and passes the result down,
 * using the same resolved value for the spec-hash computation and for the create/update call — if
 * the two disagreed, the hash written would never equal the hash compared and reconciliation would
 * re-update forever.
 *
 * <p>There is at most one binding per Deployment. When several Services select the same Deployment
 * the resolver picks deterministically and warns; a nondeterministic tie-break here would make the
 * spec-hash unstable, which shows up as a Swarm version index that never stops climbing.
 *
 * @param serviceNamespace Namespace of the Rigger Service (always the Deployment's own namespace).
 * @param serviceName      Name of the Rigger Service — part of the Traefik router name, so it must
 *                         be stable.
 * @param type             The Service's type; only {@code LOAD_BALANCER} produces anything.
 * @param ports            The Service's declared ports.
 * @param ingress          Optional ingress, may be null.
 */
public record ServiceBinding(
        String serviceNamespace,
        String serviceName,
        ServiceType type,
        List<ServicePort> ports,
        IngressSpec ingress
) {
    public ServiceBinding {
        if (serviceNamespace == null || serviceNamespace.isBlank())
            throw new IllegalArgumentException("ServiceBinding namespace must not be blank");
        if (serviceName == null || serviceName.isBlank())
            throw new IllegalArgumentException("ServiceBinding service name must not be blank");
        if (type == null) type = ServiceType.CLUSTER_IP;
        ports = ports == null ? List.of() : List.copyOf(ports);
    }

    /** True when this binding should publish ports on the Swarm service. */
    public boolean publishesPorts() {
        return type == ServiceType.LOAD_BALANCER && !ports.isEmpty();
    }

    /** True when this binding declares an HTTP ingress that Traefik should route. */
    public boolean hasIngress() {
        return ingress != null && type == ServiceType.LOAD_BALANCER;
    }

    /**
     * Container port Traefik should send traffic to: the first declared port's {@code targetPort},
     * in the Service's own declaration order. Traefik talks to the container over the overlay
     * network, so this is the target port, never the published one.
     */
    public int routeTargetPort() {
        if (ports.isEmpty()) throw new IllegalStateException("ServiceBinding has no ports");
        return ports.get(0).targetPort();
    }

    /** Traefik router/service name for this binding — unique across namespaces. */
    public String routerName() {
        return "rigger-" + serviceNamespace + "-" + serviceName;
    }

    /**
     * Stable identity of the ports this binding publishes, sorted so YAML ordering changes that
     * mean nothing to Swarm don't churn the spec-hash.
     */
    public String portsSignature() {
        if (!publishesPorts()) return "noports";
        return ports.stream()
                .sorted(Comparator.comparingInt(ServicePort::port)
                        .thenComparingInt(ServicePort::targetPort)
                        .thenComparing(p -> p.protocol() == null ? "TCP" : p.protocol().toUpperCase()))
                .map(p -> p.port() + ":" + p.targetPort() + "/" + (p.protocol() == null ? "TCP" : p.protocol().toUpperCase()))
                .collect(Collectors.joining(","));
    }

    /** Stable identity of the ingress, or {@code "noing"} when there is none. */
    public String ingressSignature() {
        return hasIngress() ? routerName() + "|" + ingress.signature() : "noing";
    }
}
