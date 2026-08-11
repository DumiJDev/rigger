package io.rigger.swarm.adapter;

import io.rigger.core.domain.resource.ServiceBinding;
import io.rigger.swarm.config.IngressProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the Traefik v3 label set that exposes a Rigger workload through the ingress controller.
 *
 * <p>Pure and static on purpose. A wrong label <em>name</em> produces no error anywhere: Traefik
 * ignores labels it doesn't recognise, the Swarm service looks perfectly healthy, and the only
 * symptom is a 404 on a real HTTP request. Keeping construction in a pure function means the exact
 * emitted key set is unit-testable without Docker, which is the only cheap defence against that.
 *
 * <p><strong>Traefik v3 spellings only.</strong> With the {@code providers.swarm} provider the
 * per-service keys are {@code traefik.swarm.network} and {@code traefik.swarm.lbswarm}. The v2
 * spellings ({@code traefik.docker.network}, {@code traefik.docker.lbswarm}) are silently ignored by
 * v3 — same 404, no log line. Do not "restore" them.
 */
public final class TraefikLabels {

    public static final String ENABLE = "traefik.enable";

    private TraefikLabels() {}

    /**
     * @param binding   the resolved Service binding; must have an ingress
     * @param networkName Docker network <em>name</em> Traefik should use to reach the container.
     *                    Traefik matches the label against the network name; the Swarm task
     *                    attachment, by contrast, is made by network ID (see {@code NetworkAdapter}).
     * @param props     cluster ingress configuration (entrypoint names, cert resolver)
     */
    public static Map<String, String> forBinding(ServiceBinding binding, String networkName, IngressProperties props) {
        if (binding == null || !binding.hasIngress())
            throw new IllegalArgumentException("TraefikLabels.forBinding requires a binding with an ingress");

        var ingress = binding.ingress();
        String router = binding.routerName();
        boolean tls   = ingress.tlsEnabled();

        var labels = new LinkedHashMap<String, String>();
        labels.put(ENABLE, "true");
        labels.put("traefik.swarm.network", networkName);
        // Let Swarm's own VIP load-balance across tasks rather than Traefik discovering task IPs.
        labels.put("traefik.swarm.lbswarm", "true");

        labels.put("traefik.http.routers." + router + ".rule", rule(ingress.host(), ingress.path()));
        labels.put("traefik.http.routers." + router + ".entrypoints",
                tls ? props.getTlsEntryPoint() : props.getEntryPoint());
        labels.put("traefik.http.routers." + router + ".service", router);
        if (tls) {
            labels.put("traefik.http.routers." + router + ".tls", "true");
            // Omit the key entirely when no resolver is configured. An empty certresolver value is
            // not "no ACME" to Traefik — it is an unknown resolver, and the router drops out.
            if (!props.getCertResolver().isBlank()) {
                labels.put("traefik.http.routers." + router + ".tls.certresolver", props.getCertResolver());
            }
        }
        labels.put("traefik.http.services." + router + ".loadbalancer.server.port",
                Integer.toString(binding.routeTargetPort()));
        return labels;
    }

    /** {@code Host(`h`)}, optionally {@code && PathPrefix(`/p`)}. Backticks are Traefik v3 syntax. */
    static String rule(String host, String path) {
        String r = "Host(`" + host + "`)";
        if (path != null && !path.isBlank()) r += " && PathPrefix(`" + path + "`)";
        return r;
    }
}
