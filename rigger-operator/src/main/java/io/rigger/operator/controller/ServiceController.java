package io.rigger.operator.controller;

import io.rigger.core.domain.resource.*;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.*;
import io.rigger.operator.diff.*;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciles Rigger Service resources.
 * A Rigger Service maps to a Docker overlay network VIP (ClusterIP)
 * or a published port with Traefik routing labels (LoadBalancer).
 *
 * <p>Note: Docker Swarm handles internal service discovery natively via DNS.
 * ClusterIP Services are therefore mostly label-only resources in Rigger —
 * they control routing annotations on the Deployment service.
 */
@Component
public class ServiceController {

    private static final Logger log = LoggerFactory.getLogger(ServiceController.class);

    private final ResourceRepository store;
    private final ServiceAdapter     swarm;
    private final ResourceDiffer     differ;
    private final RiggerEventBus     eventBus;

    public ServiceController(ResourceRepository store, ServiceAdapter swarm,
                              ResourceDiffer differ, RiggerEventBus eventBus) {
        this.store    = store;
        this.swarm    = swarm;
        this.differ   = differ;
        this.eventBus = eventBus;
    }

    public int reconcile() {
        var desired = store.findAllByKind("Service");
        if (desired.isEmpty()) return 0;

        log.debug("ServiceController: {} services in store", desired.size());
        // Service reconciliation applies port labels to existing Swarm services
        // Full implementation in Phase 5 (ingress controller integration)
        return 0;
    }
}
