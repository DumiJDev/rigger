package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Service;
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
 * Reconciles Rigger Deployment resources against Docker Swarm services.
 * Uses docker-java Service type — no manual filter encoding required.
 */
@Component
public class DeploymentController {

    private static final Logger log = LoggerFactory.getLogger(DeploymentController.class);

    private final ResourceRepository store;
    private final ServiceAdapter     swarm;
    private final ResourceDiffer     differ;
    private final RiggerEventBus     eventBus;
    private final ObjectMapper       mapper = new ObjectMapper();

    public DeploymentController(ResourceRepository store, ServiceAdapter swarm,
                                 ResourceDiffer differ, RiggerEventBus eventBus) {
        this.store = store; this.swarm = swarm; this.differ = differ; this.eventBus = eventBus;
    }

    public int reconcile() {
        var desired = store.findAllByKind("Deployment");
        var actual  = swarm.listManaged();          // returns List<Service> from docker-java
        var plan    = differ.diff(desired, actual, DeploymentSpec.class, swarm::computeSpecHash);

        if (plan.isEmpty()) {
            log.debug("DeploymentController: {} in sync", plan.unchanged());
            return 0;
        }

        log.info("DeploymentController: create={} update={} delete={} unchanged={}",
            plan.toCreate().size(), plan.toUpdate().size(),
            plan.toDelete().size(), plan.unchanged());

        int changes = 0;
        for (var item : plan.toCreate()) {
            try {
                swarm.create(item.meta(), (DeploymentSpec) item.spec());
                eventBus.publish(new ResourceAppliedEvent(
                    new ResourceRef(ResourceKind.DEPLOYMENT, item.meta().namespace(), item.meta().name()),
                    "operator", true));
                changes++;
            } catch (Exception e) {
                log.error("Failed to create Deployment {}: {}", item.meta().qualifiedName(), e.getMessage());
            }
        }

        for (var item : plan.toUpdate()) {
            try {
                swarm.update(item.existing(), item.meta(), (DeploymentSpec) item.spec());
                eventBus.publish(new ResourceAppliedEvent(
                    new ResourceRef(ResourceKind.DEPLOYMENT, item.meta().namespace(), item.meta().name()),
                    "operator", false));
                changes++;
            } catch (Exception e) {
                log.error("Failed to update Deployment {}: {}", item.meta().qualifiedName(), e.getMessage());
            }
        }

        for (var svc : plan.toDelete()) {
            try {
                String namespace = svc.getSpec() != null && svc.getSpec().getLabels() != null
                    ? svc.getSpec().getLabels().get("rigger.io/namespace") : "unknown";
                String name = svc.getSpec() != null && svc.getSpec().getLabels() != null
                    ? svc.getSpec().getLabels().get("rigger.io/name") : "unknown";
                swarm.delete(svc.getId());
                eventBus.publish(new ResourceDeletedEvent(
                    new ResourceRef(ResourceKind.DEPLOYMENT, namespace, name), "operator"));
                changes++;
            } catch (Exception e) {
                log.error("Failed to delete Swarm service {}: {}", svc.getId(), e.getMessage());
            }
        }
        return changes;
    }
}
