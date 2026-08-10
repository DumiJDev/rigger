package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.EndpointSpec;
import com.github.dockerjava.api.model.PortConfig;
import com.github.dockerjava.api.model.PortConfigProtocol;
import io.rigger.core.domain.resource.*;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reconciles Rigger Service resources — MVP.
 *
 * <p>A Rigger Service doesn't own a Swarm object of its own: it resolves the target Deployment
 * by selector match, then adjusts that Deployment's underlying Swarm service published ports
 * (via {@link EndpointSpec}) to match {@link ServiceSpec#ports()}.
 *
 * <ul>
 *   <li>{@code ClusterIP} — no-op. Swarm's overlay network already gives every service
 *       DNS-resolvable internal access by name; there's nothing extra to reconcile.</li>
 *   <li>{@code LoadBalancer} — publishes the declared ports on the Swarm service (ingress mode,
 *       routed to every node).</li>
 * </ul>
 *
 * <p>Full ingress-controller-grade routing (path-based routing, TLS termination, virtual hosts)
 * is out of scope — see CLAUDE.md.
 */
@Component
public class ServiceController {

    private static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    private final ResourceRepository store;
    private final ServiceAdapter     swarm;
    private final ObjectMapper       mapper = new ObjectMapper();

    public ServiceController(ResourceRepository store, ServiceAdapter swarm) {
        this.store = store;
        this.swarm = swarm;
    }

    public int reconcile() {
        var desired = store.findAllByKind("Service");
        if (desired.isEmpty()) return 0;

        int changes = 0;
        for (var entity : desired) {
            try {
                if (reconcileOne(entity)) changes++;
            } catch (Exception e) {
                log.error("Failed to reconcile Service {}/{}: {}",
                    entity.getNamespace(), entity.getName(), e.getMessage());
            }
        }
        return changes;
    }

    private boolean reconcileOne(ResourceEntity entity) throws Exception {
        var spec = mapper.readValue(entity.getSpecJson(), ServiceSpec.class);
        if (spec.type() != ServiceType.LOAD_BALANCER) {
            // ClusterIP: Swarm's overlay network DNS already covers this — nothing to do.
            return false;
        }

        String deploymentName = resolveDeploymentName(entity.getNamespace(), spec.selector());
        if (deploymentName == null) {
            log.debug("Service {}/{}: no Deployment matches selector {}",
                entity.getNamespace(), entity.getName(), spec.selector());
            return false;
        }

        var svc = swarm.find(entity.getNamespace(), deploymentName);
        if (svc.isEmpty()) return false; // Deployment not yet reconciled onto Swarm — wait for it

        var desiredPorts = spec.ports().stream()
            .map(p -> new PortConfig()
                .withTargetPort(p.targetPort())
                .withPublishedPort(p.port())
                .withProtocol(PortConfigProtocol.valueOf(p.protocol().toUpperCase()))
                .withPublishMode(PortConfig.PublishMode.ingress))
            .toList();

        var currentPorts = svc.get().getSpec() != null && svc.get().getSpec().getEndpointSpec() != null
            ? svc.get().getSpec().getEndpointSpec().getPorts() : null;

        if (portsMatch(currentPorts, desiredPorts)) return false;

        swarm.updatePublishedPorts(svc.get(), desiredPorts);
        log.info("Service {}/{}: published ports on Deployment {} -> {}",
            entity.getNamespace(), entity.getName(), deploymentName, desiredPorts.size());
        return true;
    }

    /** Finds a Deployment in the namespace whose selector is a superset of the Service's. */
    private String resolveDeploymentName(String namespace, Map<String, String> serviceSelector) {
        for (var deployment : store.findByKindAndNamespace("Deployment", namespace)) {
            try {
                var depSpec = mapper.readValue(deployment.getSpecJson(), DeploymentSpec.class);
                if (depSpec.selector() != null && depSpec.selector().entrySet().containsAll(serviceSelector.entrySet())) {
                    return deployment.getName();
                }
            } catch (Exception ignored) {
                // Not a Deployment we can parse — skip.
            }
        }
        return null;
    }

    private boolean portsMatch(List<PortConfig> current, List<PortConfig> desired) {
        if (current == null) return desired.isEmpty();
        if (current.size() != desired.size()) return false;
        for (var d : desired) {
            boolean found = current.stream().anyMatch(c ->
                c.getTargetPort() == d.getTargetPort()
                    && Objects.equals(c.getPublishedPort(), d.getPublishedPort())
                    && c.getProtocol() == d.getProtocol());
            if (!found) return false;
        }
        return true;
    }
}
