package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the attachable overlay network shared by Traefik and the workloads it routes to.
 *
 * <p>Two things here are load-bearing:
 *
 * <ul>
 *   <li><strong>{@code withNameFilter} is a substring match.</strong> Filtering for
 *       {@code rigger-ingress} also returns {@code rigger-ingress-old}. Every lookup re-checks
 *       {@link Network#getName()} for exact equality, otherwise the ingress could silently attach
 *       workloads to an unrelated network that merely shares a prefix.</li>
 *   <li><strong>Creation is idempotent by construction.</strong> Two reconciliation cycles (or two
 *       server instances) can race; a 409 from the Engine means someone else won, so re-find instead
 *       of failing the cycle.</li>
 * </ul>
 *
 * <p>Resolved IDs are cached per name because {@code ServiceAdapter} needs the ID while computing a
 * spec-hash — a hash function must not create infrastructure as a side effect, so
 * {@link #resolveId(String)} is lookup-only and {@link #ensureOverlay(String)} (called once per cycle
 * by the ingress controller, before the workload controllers run) is the only creator.
 */
@Component
public class NetworkAdapter {

    private static final Logger log = LoggerFactory.getLogger(NetworkAdapter.class);

    /** Marks the network as Rigger's. Deliberately NOT {@code rigger.io/managed=true} — see {@code ServiceAdapter}. */
    static final String LABEL_COMPONENT = "rigger.io/component";
    static final String COMPONENT_VALUE = "ingress-network";

    private final DockerClientFactory factory;
    private final Map<String, String> idCache = new ConcurrentHashMap<>();

    public NetworkAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /** Exact-name lookup. Returns empty when the network does not exist. */
    public Optional<Network> findExact(String name) {
        try {
            return docker().listNetworksCmd()
                    .withNameFilter(name)          // substring match — hence the exact re-check below
                    .exec()
                    .stream()
                    .filter(n -> name.equals(n.getName()))
                    .findFirst();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list networks for name " + name, e);
        }
    }

    /**
     * Returns the cached or currently live ID of the network, or {@code null} when it does not
     * exist. Never creates anything — safe to call from a spec-hash computation.
     */
    public String resolveId(String name) {
        String cached = idCache.get(name);
        if (cached != null) return cached;
        var found = findExact(name).orElse(null);
        if (found == null) return null;
        idCache.put(name, found.getId());
        return found.getId();
    }

    /**
     * Creates the attachable overlay network if it is missing and returns its ID. Idempotent; also
     * refreshes the ID cache, so it must run before the workload controllers in a cycle.
     */
    public String ensureOverlay(String name) {
        var existing = findExact(name).orElse(null);
        if (existing != null) {
            idCache.put(name, existing.getId());
            if (!"overlay".equals(existing.getDriver())) {
                log.warn("Network {} exists but its driver is '{}', not 'overlay' — ingress routing "
                       + "will not work across nodes.", name, existing.getDriver());
            }
            return existing.getId();
        }
        log.info("Creating attachable overlay network for ingress: {}", name);
        try {
            var response = docker().createNetworkCmd()
                    .withName(name)
                    .withDriver("overlay")
                    .withAttachable(true)          // lets non-Swarm containers join, useful for debugging
                    .withLabels(Map.of(LABEL_COMPONENT, COMPONENT_VALUE))
                    .exec();
            idCache.put(name, response.getId());
            return response.getId();
        } catch (Exception e) {
            // Lost a create race (409 Conflict), or the network appeared between our list and create.
            var raced = findExact(name).orElse(null);
            if (raced != null) {
                idCache.put(name, raced.getId());
                return raced.getId();
            }
            throw new DockerApiException("Failed to create overlay network " + name, e);
        }
    }

    /** Drops any cached ID for the given network — used when it is removed or replaced. */
    public void invalidate(String name) {
        idCache.remove(name);
    }
}
