package io.rigger.manifest.parser;

import io.rigger.core.domain.resource.ServiceSpec;
import io.rigger.core.exception.ManifestValidationException;
import io.rigger.manifest.validator.ManifestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Passing JSON Schema is not enough for a new spec field. {@link ManifestParser} deserialises with a
 * bare ObjectMapper — {@code FAIL_ON_UNKNOWN_PROPERTIES} on — so an {@code ingress} block that the
 * schema happily accepts still fails to parse unless the record component exists. That is exactly
 * what these tests pin.
 */
class ServiceIngressManifestTest {

    private ManifestParser parser;

    @BeforeEach
    void setUp() {
        parser = new ManifestParser(new ManifestValidator(), new KindRegistry());
    }

    private static final String WITH_INGRESS = """
            apiVersion: rigger.io/v1
            kind: Service
            metadata:
              name: shop-svc
              namespace: ns-h
            spec:
              type: LoadBalancer
              selector:
                app: shop-web
              ports:
                - port: 80
                  targetPort: 80
              ingress:
                host: shop.example.com
                tls: true
            """;

    @Test
    void parsesIngressFieldsOnTheServiceKind() throws Exception {
        var parsed = parser.parseString(WITH_INGRESS, "test.yaml");
        assertEquals(1, parsed.size());
        var spec = (ServiceSpec) parsed.get(0).manifest().spec();
        assertNotNull(spec.ingress());
        assertEquals("shop.example.com", spec.ingress().host());
        assertTrue(spec.ingress().tlsEnabled());
        assertNull(spec.ingress().path());
    }

    @Test
    void serviceWithoutIngressStillParses() throws Exception {
        String yaml = WITH_INGRESS.substring(0, WITH_INGRESS.indexOf("  ingress:"));
        var spec = (ServiceSpec) parser.parseString(yaml, "test.yaml").get(0).manifest().spec();
        assertNull(spec.ingress());
    }

    @Test
    void ingressOnClusterIpIsRejectedRatherThanSilentlyIgnored() {
        String yaml = WITH_INGRESS.replace("type: LoadBalancer", "type: ClusterIP");
        var e = assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test.yaml"));
        assertTrue(e.getMessage().contains("LoadBalancer"), e.getMessage());
    }

    @Test
    void hostWithASchemeIsRejectedAtParseTime() {
        String yaml = WITH_INGRESS.replace("shop.example.com", "https://shop.example.com");
        assertThrows(ManifestValidationException.class, () -> parser.parseString(yaml, "test.yaml"));
    }
}
