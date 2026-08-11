package io.rigger.swarm.adapter;

import io.rigger.core.domain.resource.*;
import io.rigger.swarm.config.IngressProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The only cheap defence against a wrong Traefik label name. A misspelled key produces no error
 * anywhere — Traefik ignores it, the Swarm service looks healthy, and the sole symptom is a 404 on a
 * real HTTP request. These assertions pin the exact key set.
 */
class TraefikLabelsTest {

    private static IngressProperties props() {
        var p = new IngressProperties();
        p.setEnabled(true);
        p.setNetwork("rigger-ingress");
        p.setEntryPoint("web");
        p.setTlsEntryPoint("websecure");
        return p;
    }

    private static ServiceBinding binding(IngressSpec ingress) {
        return new ServiceBinding("ns-h", "shop", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(80, 8080, null)), ingress);
    }

    @Test
    void plainHttpEmitsTheV3SwarmSpellings() {
        var labels = TraefikLabels.forBinding(binding(new IngressSpec("shop.example.com", false)),
                "rigger-ingress", props());

        assertEquals("true", labels.get("traefik.enable"));
        assertEquals("rigger-ingress", labels.get("traefik.swarm.network"));
        assertEquals("true", labels.get("traefik.swarm.lbswarm"));
        assertEquals("Host(`shop.example.com`)", labels.get("traefik.http.routers.rigger-ns-h-shop.rule"));
        assertEquals("web", labels.get("traefik.http.routers.rigger-ns-h-shop.entrypoints"));
        assertEquals("rigger-ns-h-shop", labels.get("traefik.http.routers.rigger-ns-h-shop.service"));
        assertEquals("8080", labels.get("traefik.http.services.rigger-ns-h-shop.loadbalancer.server.port"));

        // v2 spellings must never appear: on v3 they are silently ignored.
        assertFalse(labels.containsKey("traefik.docker.network"));
        assertFalse(labels.containsKey("traefik.docker.lbswarm"));
        // No TLS keys at all when tls is off.
        assertTrue(labels.keySet().stream().noneMatch(k -> k.contains(".tls")));
    }

    @Test
    void tlsSwitchesEntrypointAndSetsTlsButOmitsCertResolverWhenUnset() {
        var labels = TraefikLabels.forBinding(binding(new IngressSpec("shop.example.com", true)),
                "rigger-ingress", props());

        assertEquals("websecure", labels.get("traefik.http.routers.rigger-ns-h-shop.entrypoints"));
        assertEquals("true", labels.get("traefik.http.routers.rigger-ns-h-shop.tls"));
        // An empty certresolver value is not "no ACME" to Traefik — it is an unknown resolver, and
        // the router silently drops out. The key must be absent, not blank.
        assertFalse(labels.containsKey("traefik.http.routers.rigger-ns-h-shop.tls.certresolver"));
    }

    @Test
    void certResolverIsEmittedWhenConfigured() {
        var p = props();
        p.setCertResolver("letsencrypt");
        var labels = TraefikLabels.forBinding(binding(new IngressSpec("shop.example.com", true)),
                "rigger-ingress", p);
        assertEquals("letsencrypt", labels.get("traefik.http.routers.rigger-ns-h-shop.tls.certresolver"));
    }

    @Test
    void pathPrefixIsAppendedToTheRule() {
        var labels = TraefikLabels.forBinding(binding(new IngressSpec("shop.example.com", "/checkout", false)),
                "rigger-ingress", props());
        assertEquals("Host(`shop.example.com`) && PathPrefix(`/checkout`)",
                labels.get("traefik.http.routers.rigger-ns-h-shop.rule"));
    }

    @Test
    void rejectsABindingWithNoIngress() {
        var noIngress = binding(null);
        assertThrows(IllegalArgumentException.class,
                () -> TraefikLabels.forBinding(noIngress, "rigger-ingress", props()));
    }
}
