package io.rigger.core.domain.resource;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServiceBindingTest {

    private static ServiceBinding binding(ServiceType type, IngressSpec ingress, ServicePort... ports) {
        return new ServiceBinding("ns-a", "web-svc", type, List.of(ports), ingress);
    }

    @Test
    void clusterIpNeitherPublishesNorRoutes() {
        var b = binding(ServiceType.CLUSTER_IP, new IngressSpec("a.example.com", true), new ServicePort(80, 8080, null));
        assertFalse(b.publishesPorts());
        assertFalse(b.hasIngress());
        assertEquals("noports", b.portsSignature());
        assertEquals("noing", b.ingressSignature());
    }

    @Test
    void routerNameIsNamespaceQualified() {
        assertEquals("rigger-ns-a-web-svc",
                binding(ServiceType.LOAD_BALANCER, null, new ServicePort(80, 8080, null)).routerName());
    }

    @Test
    void routesToFirstDeclaredTargetPortNotThePublishedPort() {
        var b = binding(ServiceType.LOAD_BALANCER, new IngressSpec("a.example.com", false),
                new ServicePort(8081, 8080, null), new ServicePort(443, 8443, null));
        assertEquals(8080, b.routeTargetPort());
    }

    @Test
    void portsSignatureIgnoresDeclarationOrder() {
        var a = new ServiceBinding("ns-a", "s", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(80, 8080, null), new ServicePort(443, 8443, "TCP")), null);
        var b = new ServiceBinding("ns-a", "s", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(443, 8443, "tcp"), new ServicePort(80, 8080, "TCP")), null);
        assertEquals(a.portsSignature(), b.portsSignature());
    }

    @Test
    void portsSignatureChangesWhenAPortChanges() {
        var a = binding(ServiceType.LOAD_BALANCER, null, new ServicePort(80, 8080, null));
        var b = binding(ServiceType.LOAD_BALANCER, null, new ServicePort(8081, 8080, null));
        assertNotEquals(a.portsSignature(), b.portsSignature());
    }

    @Test
    void ingressSignatureIncludesRouterNameSoTwoNamespacesNeverCollide() {
        var ingress = new IngressSpec("a.example.com", true);
        var inA = new ServiceBinding("ns-a", "s", ServiceType.LOAD_BALANCER, List.of(new ServicePort(80, 80, null)), ingress);
        var inB = new ServiceBinding("ns-b", "s", ServiceType.LOAD_BALANCER, List.of(new ServicePort(80, 80, null)), ingress);
        assertNotEquals(inA.ingressSignature(), inB.ingressSignature());
    }
}
