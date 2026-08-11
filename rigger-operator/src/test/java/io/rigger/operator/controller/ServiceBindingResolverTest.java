package io.rigger.operator.controller;

import io.rigger.core.domain.resource.ServiceType;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceBindingResolverTest {

    private ResourceRepository store;
    private ServiceBindingResolver resolver;
    private final List<ResourceEntity> deployments = new ArrayList<>();
    private final List<ResourceEntity> services    = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = mock(ResourceRepository.class);
        when(store.findAllByKind("Deployment")).thenReturn(deployments);
        when(store.findAllByKind("Service")).thenReturn(services);
        resolver = new ServiceBindingResolver(store);
        deployments.clear();
        services.clear();
    }

    private void deployment(String ns, String name, String selectorJson) {
        deployments.add(new ResourceEntity(ns + "/" + name, "Deployment", ns, name,
                "{\"image\":\"nginx:alpine\",\"replicas\":1,\"selector\":" + selectorJson + "}", null, "test"));
    }

    private void service(String ns, String name, String specJson) {
        services.add(new ResourceEntity(ns + "/" + name, "Service", ns, name, specJson, null, "test"));
    }

    private static String lb(String selectorJson, String ingressJson) {
        return "{\"type\":\"LoadBalancer\",\"selector\":" + selectorJson
                + ",\"ports\":[{\"port\":80,\"targetPort\":8080}]"
                + (ingressJson == null ? "" : ",\"ingress\":" + ingressJson) + "}";
    }

    @Test
    void bindsByDeploymentSelectorSuperset() {
        deployment("ns-h", "web", "{\"app\":\"web\",\"tier\":\"front\"}");
        service("ns-h", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\",\"tls\":true}"));

        var bindings = resolver.resolveAll();
        var binding = bindings.get("ns-h/web");
        assertNotNull(binding);
        assertEquals("shop", binding.serviceName());
        assertTrue(binding.hasIngress());
        assertEquals("shop.example.com", binding.ingress().host());
        assertEquals(8080, binding.routeTargetPort());
    }

    @Test
    void selectorThatIsNotASubsetDoesNotBind() {
        deployment("ns-h", "web", "{\"app\":\"web\"}");
        service("ns-h", "shop", lb("{\"app\":\"web\",\"tier\":\"front\"}", null));
        assertTrue(resolver.resolveAll().isEmpty());
    }

    @Test
    void clusterIpProducesNoBinding() {
        deployment("ns-h", "web", "{\"app\":\"web\"}");
        service("ns-h", "internal",
                "{\"type\":\"ClusterIP\",\"selector\":{\"app\":\"web\"},\"ports\":[{\"port\":80,\"targetPort\":80}]}");
        assertTrue(resolver.resolveAll().isEmpty());
    }

    @Test
    void neverCrossesNamespaces() {
        deployment("ns-h", "web", "{\"app\":\"web\"}");
        service("ns-other", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\"}"));
        assertTrue(resolver.resolveAll().isEmpty());
    }

    @Test
    void tieBreakBetweenTwoServicesIsDeterministicRegardlessOfStoreOrder() {
        deployment("ns-h", "web", "{\"app\":\"web\"}");
        service("ns-h", "b-svc", lb("{\"app\":\"web\"}", "{\"host\":\"b.example.com\"}"));
        service("ns-h", "a-svc", lb("{\"app\":\"web\"}", "{\"host\":\"a.example.com\"}"));

        var first = resolver.resolveAll().get("ns-h/web");
        assertEquals("a-svc", first.serviceName());

        // Same data, opposite query order — the winner must not change, or the spec-hash would flip
        // between cycles and the Swarm version index would climb forever.
        Collections.reverse(services);
        assertEquals("a-svc", resolver.resolveAll().get("ns-h/web").serviceName());
    }

    @Test
    void tieBreakBetweenTwoDeploymentsIsDeterministic() {
        deployment("ns-h", "z-web", "{\"app\":\"web\"}");
        deployment("ns-h", "a-web", "{\"app\":\"web\"}");
        service("ns-h", "shop", lb("{\"app\":\"web\"}", null));

        var bindings = resolver.resolveAll();
        assertEquals(1, bindings.size());
        assertTrue(bindings.containsKey("ns-h/a-web"));

        Collections.reverse(deployments);
        assertTrue(resolver.resolveAll().containsKey("ns-h/a-web"));
    }

    @Test
    void duplicateHostAcrossNamespacesKeepsOnlyTheFirstClaimAndPreservesPorts() {
        deployment("team-a", "web", "{\"app\":\"web\"}");
        deployment("team-b", "web", "{\"app\":\"web\"}");
        service("team-b", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\"}"));
        service("team-a", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\"}"));

        var bindings = resolver.resolveAll();
        assertTrue(bindings.get("team-a/web").hasIngress(), "team-a sorts first and keeps the host");
        assertFalse(bindings.get("team-b/web").hasIngress(), "the later claim loses its ingress");
        // Losing an ingress race is not a reason to unpublish a Service's ports.
        assertTrue(bindings.get("team-b/web").publishesPorts());
        assertEquals(ServiceType.LOAD_BALANCER, bindings.get("team-b/web").type());
    }

    @Test
    void sameHostTwiceIsResolvedIdenticallyRegardlessOfStoreOrder() {
        deployment("team-a", "web", "{\"app\":\"web\"}");
        deployment("team-b", "web", "{\"app\":\"web\"}");
        service("team-a", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\"}"));
        service("team-b", "shop", lb("{\"app\":\"web\"}", "{\"host\":\"shop.example.com\"}"));

        Collections.reverse(services);
        var bindings = resolver.resolveAll();
        assertTrue(bindings.get("team-a/web").hasIngress());
        assertFalse(bindings.get("team-b/web").hasIngress());
    }

    @Test
    void unreadableServiceSpecIsSkippedNotFatal() {
        deployment("ns-h", "web", "{\"app\":\"web\"}");
        service("ns-h", "broken", "{ not json");
        service("ns-h", "shop", lb("{\"app\":\"web\"}", null));
        assertEquals(1, resolver.resolveAll().size());
    }

    @Test
    void noServicesMeansNoLookupsAtAll() {
        assertTrue(resolver.resolveAll().isEmpty());
        verify(store, never()).findAllByKind("Deployment");
    }
}
