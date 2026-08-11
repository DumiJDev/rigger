package io.rigger.core.domain.resource;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IngressSpecTest {

    @Test
    void normalisesHostAndDefaultsTls() {
        var ingress = new IngressSpec("  Shop.Example.COM ", null, null);
        assertEquals("shop.example.com", ingress.host());
        assertFalse(ingress.tlsEnabled());
        assertNull(ingress.path());
    }

    @Test
    void rejectsSchemesPortsAndPaths() {
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec("http://shop.example.com", true));
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec("shop.example.com:8080", true));
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec("shop.example.com/checkout", true));
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec("  ", true));
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec(null, true));
    }

    @Test
    void acceptsWildcardAndSingleLabelHosts() {
        assertEquals("*.example.com", new IngressSpec("*.example.com", true).host());
        assertEquals("localhost", new IngressSpec("localhost", false).host());
    }

    @Test
    void pathMustBeAbsoluteAndBlankBecomesNull() {
        assertNull(new IngressSpec("a.example.com", "   ", false).path());
        assertEquals("/api", new IngressSpec("a.example.com", "/api", false).path());
        assertThrows(IllegalArgumentException.class, () -> new IngressSpec("a.example.com", "api", false));
    }

    @Test
    void signatureIsStableAndDistinguishesEveryField() {
        var base = new IngressSpec("a.example.com", "/api", false);
        assertEquals(base.signature(), new IngressSpec("a.example.com", "/api", false).signature());
        assertNotEquals(base.signature(), new IngressSpec("b.example.com", "/api", false).signature());
        assertNotEquals(base.signature(), new IngressSpec("a.example.com", "/other", false).signature());
        assertNotEquals(base.signature(), new IngressSpec("a.example.com", "/api", true).signature());
    }

    @Test
    void serviceSpecKeepsThreeArgumentConstructionPath() {
        // ComposeConverter constructs a ServiceSpec with no ingress and must keep compiling.
        var spec = new ServiceSpec(Map.of("app", "web"), List.of(new ServicePort(80, 80, null)),
                ServiceType.CLUSTER_IP);
        assertNull(spec.ingress());
        assertEquals(ServiceType.CLUSTER_IP, spec.type());
    }
}
