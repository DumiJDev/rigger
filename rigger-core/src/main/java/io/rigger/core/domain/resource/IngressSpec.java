package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.regex.Pattern;

/**
 * HTTP ingress for a Rigger {@link ServiceSpec} — a host (and optional path prefix) that the
 * cluster's Traefik ingress controller should route to the Service's target Deployment.
 *
 * <p>Deliberately fields on the existing Service kind rather than a separate {@code Ingress} kind:
 * a Service already carries the selector and the port, which is everything a router needs.
 *
 * <pre>
 * kind: Service
 * spec:
 *   type: LoadBalancer
 *   selector: {app: shop-web}
 *   ports: [{port: 80, targetPort: 80}]
 *   ingress:
 *     host: shop.example.com
 *     tls: true
 * </pre>
 *
 * <p>{@code tls} is a boxed {@link Boolean} on purpose: a primitive {@code boolean} component makes
 * an explicit {@code tls: null} in YAML fail deserialisation rather than defaulting. Read it through
 * {@link #tlsEnabled()}.
 *
 * @param host  Virtual host to route, e.g. {@code shop.example.com}. Required.
 * @param path  Optional path prefix; when set the router rule becomes
 *              {@code Host(`h`) && PathPrefix(`/p`)}.
 * @param tls   Terminate TLS at the ingress controller. With no ACME cert resolver configured
 *              cluster-side, Traefik serves its own self-signed certificate — which is why this
 *              works on a single-node CI runner where ACME cannot.
 */
public record IngressSpec(
        @JsonProperty("host") String host,
        @JsonProperty("path") String path,
        @JsonProperty("tls") Boolean tls
) {
    /**
     * Deliberately permissive: a hostname label set, optionally with a leading wildcard label.
     * Rejects schemes, ports and paths, which are the mistakes that actually happen — a bad host
     * silently produces a router rule nothing ever matches, and the only way to notice is an HTTP
     * request against Traefik.
     */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    public IngressSpec {
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Service ingress.host must not be blank");
        host = host.trim().toLowerCase();
        if (host.length() > 253 || !HOST_PATTERN.matcher(host).matches())
            throw new IllegalArgumentException(
                    "Service ingress.host '" + host + "' must be a bare hostname (no scheme, port or path)");
        if (path != null) {
            path = path.trim();
            if (path.isEmpty()) path = null;
            else if (!path.startsWith("/"))
                throw new IllegalArgumentException("Service ingress.path '" + path + "' must start with '/'");
        }
        if (tls == null) tls = Boolean.FALSE;
    }

    /** Convenience constructor for the common {@code host} + {@code tls} case. */
    public IngressSpec(String host, boolean tls) {
        this(host, null, tls);
    }

    public boolean tlsEnabled() { return Boolean.TRUE.equals(tls); }

    /**
     * Stable, order-independent string identity of this ingress, for folding into a Swarm service's
     * {@code spec-hash}. Must never depend on map or set iteration order — an unstable value here
     * makes the Swarm version index climb forever instead of converging.
     */
    public String signature() {
        return host + "|" + (path == null ? "" : path) + "|" + tlsEnabled();
    }
}
