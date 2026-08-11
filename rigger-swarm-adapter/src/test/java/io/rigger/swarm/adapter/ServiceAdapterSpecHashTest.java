package io.rigger.swarm.adapter;

import io.rigger.core.domain.resource.*;
import io.rigger.swarm.client.DockerClientFactory;
import io.rigger.swarm.config.IngressProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The spec-hash has two failure modes that live on the same line and each passes the other's test:
 *
 * <ul>
 *   <li>fold in too little, and a changed ingress never reaches Swarm — the labels are simply never
 *       applied, because the diff sees no difference;</li>
 *   <li>fold in something unstable, and the hash changes on its own every cycle — the Swarm version
 *       index climbs forever and never converges.</li>
 * </ul>
 *
 * Both directions are asserted here. Compilation says nothing about either.
 */
class ServiceAdapterSpecHashTest {

    private ConfigAdapter configs;
    private NetworkAdapter networks;
    private IngressProperties props;
    private ServiceAdapter adapter;

    private static final ObjectMeta META = new ObjectMeta("web", "ns-h", Map.of(), Map.of());

    private static DeploymentSpec deployment(int replicas) {
        return new DeploymentSpec(replicas, Map.of("app", "web"), "nginx:alpine",
                List.of(), null, null, null, List.of(), List.of());
    }

    private static ServiceBinding binding(IngressSpec ingress, ServiceType type) {
        return new ServiceBinding("ns-h", "shop", type, List.of(new ServicePort(80, 8080, null)), ingress);
    }

    @BeforeEach
    void setUp() {
        configs  = mock(ConfigAdapter.class);
        networks = mock(NetworkAdapter.class);
        props    = new IngressProperties();
        props.setEnabled(true);
        props.setNetwork("rigger-ingress");
        when(configs.find(anyString(), anyString())).thenReturn(Optional.empty());
        when(networks.resolveId("rigger-ingress")).thenReturn("netid123");
        adapter = new ServiceAdapter(mock(DockerClientFactory.class), configs, networks, props);
    }

    @Test
    void hashIsStableAcrossRepeatedCalls() {
        var b = binding(new IngressSpec("shop.example.com", true), ServiceType.LOAD_BALANCER);
        String first = adapter.computeSpecHash(META, deployment(2), b);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, adapter.computeSpecHash(META, deployment(2), b),
                    "spec-hash must not vary between cycles — an unstable hash makes the Swarm "
                  + "version index climb forever");
        }
    }

    @Test
    void deploymentWithNoBindingKeepsThePreIngressHash() {
        // Nothing is appended for a null binding, so existing Deployments with no Service don't all
        // churn once on upgrade.
        assertEquals(Integer.toHexString(deployment(2).hashCode()),
                adapter.computeSpecHash(META, deployment(2), null));
    }

    @Test
    void changingTheIngressHostChangesTheHash() {
        String before = adapter.computeSpecHash(META, deployment(2),
                binding(new IngressSpec("shop.example.com", true), ServiceType.LOAD_BALANCER));
        String after = adapter.computeSpecHash(META, deployment(2),
                binding(new IngressSpec("store.example.com", true), ServiceType.LOAD_BALANCER));
        assertNotEquals(before, after);
    }

    @Test
    void togglingTlsChangesTheHash() {
        assertNotEquals(
                adapter.computeSpecHash(META, deployment(2), binding(new IngressSpec("a.example.com", false), ServiceType.LOAD_BALANCER)),
                adapter.computeSpecHash(META, deployment(2), binding(new IngressSpec("a.example.com", true), ServiceType.LOAD_BALANCER)));
    }

    @Test
    void removingTheIngressChangesTheHash() {
        assertNotEquals(
                adapter.computeSpecHash(META, deployment(2), binding(new IngressSpec("a.example.com", true), ServiceType.LOAD_BALANCER)),
                adapter.computeSpecHash(META, deployment(2), binding(null, ServiceType.LOAD_BALANCER)));
    }

    @Test
    void disablingIngressClusterWideChangesTheHashSoStaleLabelsAreRemoved() {
        var b = binding(new IngressSpec("a.example.com", true), ServiceType.LOAD_BALANCER);
        String enabled = adapter.computeSpecHash(META, deployment(2), b);
        props.setEnabled(false);
        assertNotEquals(enabled, adapter.computeSpecHash(META, deployment(2), b),
                "flipping rigger.ingress.enabled to false must change the hash, or the Traefik "
              + "labels already on the service are never removed");
    }

    @Test
    void renamingAnEntrypointChangesTheHash() {
        var b = binding(new IngressSpec("a.example.com", false), ServiceType.LOAD_BALANCER);
        String before = adapter.computeSpecHash(META, deployment(2), b);
        props.setEntryPoint("public");
        assertNotEquals(before, adapter.computeSpecHash(META, deployment(2), b));
    }

    @Test
    void recreatingTheOverlayNetworkChangesTheHash() {
        var b = binding(new IngressSpec("a.example.com", false), ServiceType.LOAD_BALANCER);
        String before = adapter.computeSpecHash(META, deployment(2), b);
        when(networks.resolveId("rigger-ingress")).thenReturn("a-different-network-id");
        assertNotEquals(before, adapter.computeSpecHash(META, deployment(2), b),
                "the task's network attachment is by ID, so a recreated network must re-attach");
    }

    @Test
    void portsAreFoldedInSoPublishedPortChangesConverge() {
        var one = new ServiceBinding("ns-h", "shop", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(80, 8080, null)), null);
        var two = new ServiceBinding("ns-h", "shop", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(8081, 8080, null)), null);
        assertNotEquals(adapter.computeSpecHash(META, deployment(2), one),
                        adapter.computeSpecHash(META, deployment(2), two));
    }

    @Test
    void portDeclarationOrderDoesNotAffectTheHash() {
        var a = new ServiceBinding("ns-h", "shop", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(80, 8080, null), new ServicePort(443, 8443, null)), null);
        var b = new ServiceBinding("ns-h", "shop", ServiceType.LOAD_BALANCER,
                List.of(new ServicePort(443, 8443, null), new ServicePort(80, 8080, null)), null);
        assertEquals(adapter.computeSpecHash(META, deployment(2), a),
                     adapter.computeSpecHash(META, deployment(2), b));
    }

    @Test
    void missingOverlayNetworkFallsBackToNoIngressRatherThanVarying() {
        when(networks.resolveId("rigger-ingress")).thenReturn(null);
        var withIngress = binding(new IngressSpec("a.example.com", true), ServiceType.LOAD_BALANCER);
        var noIngress   = binding(null, ServiceType.LOAD_BALANCER);
        assertEquals(adapter.computeSpecHash(META, deployment(2), noIngress),
                     adapter.computeSpecHash(META, deployment(2), withIngress));
        verify(networks, atLeastOnce()).resolveId("rigger-ingress");
    }

    @Test
    void hashNeverCreatesTheOverlayNetwork() {
        adapter.computeSpecHash(META, deployment(2),
                binding(new IngressSpec("a.example.com", true), ServiceType.LOAD_BALANCER));
        verify(networks, never()).ensureOverlay(any());
    }
}
