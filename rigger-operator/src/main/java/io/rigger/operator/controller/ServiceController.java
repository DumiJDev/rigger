package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.*;
import io.rigger.store.entity.ResourceEntity;
import io.rigger.store.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Observes Rigger Service resources. <strong>Writes nothing.</strong>
 *
 * <p>A Rigger Service owns no Swarm object of its own: it describes how a Deployment's Swarm service
 * should be exposed — published ports, and optionally a Traefik ingress. Both are now produced inside
 * {@code ServiceAdapter.buildServiceSpec()} from the {@link ServiceBinding} that
 * {@link ServiceBindingResolver} resolves for each Deployment, and applied by
 * {@code DeploymentController}.
 *
 * <p><strong>Why this controller stopped writing.</strong> It used to call
 * {@code ServiceAdapter.updatePublishedPorts()}, which re-sent a whole spec it had read earlier —
 * concurrently with {@code DeploymentController} rewriting the same service on another virtual
 * thread. Whichever finished last won, so ports (and later, Traefik labels and the ingress network
 * attachment) vanished at random and the two writers re-raced every 15 seconds. The version index of
 * an idle Swarm service climbed by the second. There is now exactly one writer per Swarm service.
 *
 * <ul>
 *   <li>{@code ClusterIP} — nothing to do at all; Swarm's overlay DNS already resolves service names
 *       inside the cluster.</li>
 *   <li>{@code LoadBalancer} — reported here only: a Service whose selector matches no Deployment
 *       used to be silently invisible, which reads as "ingress is broken" rather than "the selector
 *       is wrong".</li>
 * </ul>
 */
@Component
public class ServiceController {

    private static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    private final ResourceRepository store;
    private final ObjectMapper       mapper = new ObjectMapper();

    public ServiceController(ResourceRepository store) {
        this.store = store;
    }

    /** @return always 0 — this controller makes no changes. */
    public int reconcile() {
        for (var entity : store.findAllByKind("Service")) {
            try {
                warnIfUnbound(entity);
            } catch (Exception e) {
                log.error("Failed to inspect Service {}/{}: {}",
                    entity.getNamespace(), entity.getName(), e.getMessage());
            }
        }
        return 0;
    }

    private void warnIfUnbound(ResourceEntity entity) throws Exception {
        var spec = mapper.readValue(entity.getSpecJson(), ServiceSpec.class);
        if (spec.type() != ServiceType.LOAD_BALANCER) return;
        if (matchingDeployment(entity.getNamespace(), spec.selector())) return;
        log.warn("Service {}/{} selects nothing: no Deployment in this namespace has a selector "
               + "matching {} — its ports are not published and any ingress is not routed.",
                entity.getNamespace(), entity.getName(), spec.selector());
    }

    private boolean matchingDeployment(String namespace, Map<String, String> serviceSelector) {
        for (var deployment : store.findByKindAndNamespace("Deployment", namespace)) {
            try {
                var depSpec = mapper.readValue(deployment.getSpecJson(), DeploymentSpec.class);
                if (depSpec.selector() != null
                        && depSpec.selector().entrySet().containsAll(serviceSelector.entrySet())) {
                    return true;
                }
            } catch (Exception ignored) {
                // Not a Deployment we can parse — skip.
            }
        }
        return false;
    }
}
