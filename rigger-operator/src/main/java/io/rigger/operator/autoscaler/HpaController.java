package io.rigger.operator.autoscaler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.*;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.HpaScaledEvent;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Horizontal Pod Autoscaler controller.
 * Runs every 30 seconds. For each Deployment with an HPA spec:
 * reads CPU metrics, calculates desired replicas, applies scale-down cooldown,
 * and scales the Swarm service if needed.
 */
@Component
public class HpaController {

    private static final Logger log = LoggerFactory.getLogger(HpaController.class);
    private static final long SCALE_DOWN_COOLDOWN_DEFAULT_SEC = 180;

    private final ResourceRepository store;
    private final ServiceAdapter     swarm;
    private final MetricsSource      metrics;
    private final RiggerEventBus     eventBus;
    private final ObjectMapper       mapper = new ObjectMapper();
    private final Map<String, Instant> lastScaleDown = new ConcurrentHashMap<>();

    public HpaController(ResourceRepository store, ServiceAdapter swarm,
                          MetricsSource metrics, RiggerEventBus eventBus) {
        this.store = store; this.swarm = swarm; this.metrics = metrics; this.eventBus = eventBus;
    }

    @Scheduled(fixedDelayString = "${rigger.operator.hpa-interval-seconds:30}000")
    public void reconcile() {
        store.findAllByKind("Deployment").forEach(entity -> {
            try {
                var spec = mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
                if (spec.hpa() == null) return;
                processHpa(entity.getNamespace(), entity.getName(), spec);
            } catch (Exception e) {
                log.error("HPA error for {}/{}: {}", entity.getNamespace(), entity.getName(), e.getMessage());
            }
        });
    }

    void processHpa(String namespace, String name, DeploymentSpec spec) {
        var hpa    = spec.hpa();
        var svcOpt = swarm.find(namespace, name);
        if (svcOpt.isEmpty()) return;

        var svc     = svcOpt.get();
        int current = currentReplicas(svc);
        double cpu  = metrics.averageCpuPercent(namespace, name);
        int desired = calculateDesired(current, cpu, hpa);
        String key  = namespace + "/" + name;

        if (desired == current) return;

        if (desired < current) {
            long cooldown = hpa.scaleDownCooldownSeconds() > 0
                ? hpa.scaleDownCooldownSeconds() : SCALE_DOWN_COOLDOWN_DEFAULT_SEC;
            var last = lastScaleDown.get(key);
            if (last != null && Instant.now().isBefore(last.plusSeconds(cooldown))) return;
            lastScaleDown.put(key, Instant.now());
        }

        log.info("HPA {}: {} -> {} replicas (cpu={}% target={}%)", key, current, desired,
            (int) cpu, hpa.targetCPUUtilizationPercentage());

        long version = svc.getVersion() != null ? svc.getVersion().getIndex() : 0L;
        swarm.scale(svc.getId(), version, desired);

        var ref = new ResourceRef(ResourceKind.DEPLOYMENT, namespace, name);
        eventBus.publish(new HpaScaledEvent(ref, current, desired, cpu,
            hpa.targetCPUUtilizationPercentage()));
    }

    int calculateDesired(int current, double currentCpu, HpaSpec hpa) {
        if (current == 0) return hpa.minReplicas();
        int desired = (int) Math.ceil(current * (currentCpu / hpa.targetCPUUtilizationPercentage()));
        return Math.max(hpa.minReplicas(), Math.min(hpa.maxReplicas(), desired));
    }

    private int currentReplicas(com.github.dockerjava.api.model.Service svc) {
        var spec = svc.getSpec();
        if (spec == null || spec.getMode() == null) return 0;
        var rep = spec.getMode().getReplicated();
        return rep != null ? (int) rep.getReplicas() : 0;
    }
}
