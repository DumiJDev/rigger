package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.*;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Resolves, for every Deployment, the single Rigger Service that exposes it.
 *
 * <p>{@code rigger-swarm-adapter} has no access to the resource store, so it cannot look a Service
 * up while building a Swarm spec. This resolver is the store-side half of that: the caller resolves
 * <strong>once per reconciliation cycle</strong> and uses the same map for the spec-hash lambda and
 * for the create/update calls. Resolving twice risks the two disagreeing, and a hash that doesn't
 * match what was written means the Deployment is updated again on every cycle, forever.
 *
 * <p>Determinism is a correctness requirement, not tidiness. Two rules:
 * <ul>
 *   <li>When several Services select one Deployment, the lexicographically first Service name wins
 *       and the rest are logged at WARN. A nondeterministic pick would make the spec-hash flip
 *       between cycles and the Swarm version index climb without end.</li>
 *   <li>When Services in different namespaces claim the same ingress host, the lexicographically
 *       first {@code namespace/name} keeps the ingress and the others lose it (keeping their
 *       published ports). Without this a DEPLOYER in one namespace could hijack another team's host
 *       with an ordinary apply, and Traefik would pick between the duplicate routers at random.</li>
 * </ul>
 *
 * <p>{@code ClusterIP} Services produce no binding at all: Swarm's overlay DNS already covers
 * internal access, there is nothing to publish, and ingress on a ClusterIP is rejected at apply time
 * by {@code ManifestValidator}.
 */
@Component
public class ServiceBindingResolver {

    private static final Logger log = LoggerFactory.getLogger(ServiceBindingResolver.class);

    private final ResourceRepository store;
    private final ObjectMapper mapper = new ObjectMapper();

    public ServiceBindingResolver(ResourceRepository store) {
        this.store = store;
    }

    /** @return bindings keyed by {@code "namespace/deploymentName"}; Deployments with none are absent. */
    public Map<String, ServiceBinding> resolveAll() {
        var services = store.findAllByKind("Service");
        if (services.isEmpty()) return Map.of();

        // Sorted so every tie-break below is a function of the data alone, never of query order.
        var sorted = new ArrayList<>(services);
        sorted.sort(Comparator.comparing(ResourceEntity::getNamespace).thenComparing(ResourceEntity::getName));

        var deploymentSelectors = deploymentSelectorsByNamespace();
        var byDeployment = new LinkedHashMap<String, ServiceBinding>();

        for (var entity : sorted) {
            ServiceSpec spec;
            try {
                spec = mapper.readValue(entity.getSpecJson(), ServiceSpec.class);
            } catch (Exception e) {
                log.error("Service {}/{}: unreadable spec, ignoring: {}",
                        entity.getNamespace(), entity.getName(), e.getMessage());
                continue;
            }
            if (spec.type() != ServiceType.LOAD_BALANCER) continue;

            String deployment = resolveDeploymentName(
                    deploymentSelectors.getOrDefault(entity.getNamespace(), Map.of()), spec.selector());
            if (deployment == null) {
                log.debug("Service {}/{}: no Deployment matches selector {}",
                        entity.getNamespace(), entity.getName(), spec.selector());
                continue;
            }

            String key = entity.getNamespace() + "/" + deployment;
            var binding = new ServiceBinding(entity.getNamespace(), entity.getName(),
                    spec.type(), spec.ports(), spec.ingress());
            var previous = byDeployment.putIfAbsent(key, binding);
            if (previous != null) {
                log.warn("Deployment {} is selected by more than one Service ({} and {}); keeping {} "
                       + "— split the selectors, only one Service can own a Deployment's ports.",
                        key, previous.serviceName(), binding.serviceName(), previous.serviceName());
            }
        }

        return enforceHostUniqueness(byDeployment);
    }

    /**
     * Drops the ingress from every claim on a host that a lexicographically earlier
     * {@code namespace/name} already owns. Published ports are kept — losing an ingress race is not a
     * reason to take a Service's ports away.
     */
    private Map<String, ServiceBinding> enforceHostUniqueness(Map<String, ServiceBinding> bindings) {
        var ownerByHost = new HashMap<String, String>();
        var result = new LinkedHashMap<String, ServiceBinding>();

        // Deterministic iteration by the Service's own identity, independent of Deployment keys.
        var ordered = new ArrayList<>(bindings.entrySet());
        ordered.sort(Comparator.comparing(e -> e.getValue().serviceNamespace() + "/" + e.getValue().serviceName()));

        for (var entry : ordered) {
            var binding = entry.getValue();
            if (!binding.hasIngress()) { result.put(entry.getKey(), binding); continue; }

            String host  = binding.ingress().host();
            String claim = binding.serviceNamespace() + "/" + binding.serviceName();
            String owner = ownerByHost.putIfAbsent(host, claim);
            if (owner == null) {
                result.put(entry.getKey(), binding);
            } else {
                log.warn("Ingress host '{}' is already claimed by Service {}; ignoring the ingress on "
                       + "Service {} (its published ports are unaffected). Hosts are cluster-wide, so "
                       + "two namespaces cannot share one.", host, owner, claim);
                result.put(entry.getKey(), new ServiceBinding(binding.serviceNamespace(),
                        binding.serviceName(), binding.type(), binding.ports(), null));
            }
        }
        return result;
    }

    private Map<String, Map<String, Map<String, String>>> deploymentSelectorsByNamespace() {
        var byNamespace = new HashMap<String, Map<String, Map<String, String>>>();
        for (var deployment : store.findAllByKind("Deployment")) {
            try {
                var spec = mapper.readValue(deployment.getSpecJson(), DeploymentSpec.class);
                if (spec.selector() == null) continue;
                byNamespace.computeIfAbsent(deployment.getNamespace(), k -> new TreeMap<>())
                        .put(deployment.getName(), spec.selector());
            } catch (Exception ignored) {
                // Not a Deployment we can parse — skip, same as before.
            }
        }
        return byNamespace;
    }

    /**
     * Finds the Deployment in the namespace whose selector is a superset of the Service's. Iterates a
     * TreeMap, so with more than one match the first name alphabetically wins, every cycle.
     */
    private String resolveDeploymentName(Map<String, Map<String, String>> candidates,
                                         Map<String, String> serviceSelector) {
        String chosen = null;
        for (var candidate : candidates.entrySet()) {
            if (candidate.getValue().entrySet().containsAll(serviceSelector.entrySet())) {
                if (chosen == null) {
                    chosen = candidate.getKey();
                } else {
                    log.warn("Service selector {} matches Deployments {} and {}; using {}",
                            serviceSelector, chosen, candidate.getKey(), chosen);
                }
            }
        }
        return chosen;
    }
}
