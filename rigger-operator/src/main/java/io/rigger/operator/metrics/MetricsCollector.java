package io.rigger.operator.metrics;

import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.TaskState;
import io.rigger.operator.autoscaler.MetricsSource;
import io.rigger.store.repository.NodeRepository;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.core.domain.cluster.NodeStatus;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single place that reads current cluster and Deployment metrics off Swarm and the store.
 *
 * <p>Two callers need exactly these numbers: the REST endpoints that answer "what is happening
 * right now", and {@link MetricsSampler}, which writes them to a time series. They were computed
 * separately at first and the two copies disagreed within a day — the totals must come from one
 * place or the chart and the number above it drift apart.
 *
 * <p>Every read degrades rather than throws. A Swarm hiccup during a dashboard poll should show a
 * stale or zero number, not turn into a 500 or drop a sampling round on the floor.
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final ResourceRepository store;
    private final NodeRepository     nodeRepo;
    private final ServiceAdapter     swarm;
    private final MetricsSource      metrics;

    public MetricsCollector(ResourceRepository store, NodeRepository nodeRepo,
                            ServiceAdapter swarm, MetricsSource metrics) {
        this.store = store; this.nodeRepo = nodeRepo; this.swarm = swarm; this.metrics = metrics;
    }

    /** Cluster-wide totals: node health, Swarm replica counts, and stored resource counts by kind. */
    public ClusterSnapshot cluster() {
        long active = nodeRepo.findByStatus(NodeStatus.ACTIVE).size();
        long total  = nodeRepo.count();

        int managedServices = 0, running = 0, desired = 0;
        try {
            var services = swarm.listManaged();
            managedServices = services.size();
            for (var s : services) {
                desired += desiredReplicas(s);
                running += runningTasks(s.getId());
            }
        } catch (Exception e) {
            log.debug("Swarm totals unavailable: {}", e.getMessage());
        }

        return new ClusterSnapshot(active, total, managedServices, running, desired,
            store.findAllByKind("Deployment").size(),
            store.findAllByKind("Service").size(),
            store.findAllByKind("ConfigMap").size(),
            store.findAllByKind("Secret").size());
    }

    /**
     * Per-Deployment running replicas and average CPU.
     *
     * @param desiredReplicas taken from the stored spec by the caller, which has already parsed it —
     *                        parsing it again here would mean two JSON reads per call.
     */
    public DeploymentSnapshot deployment(String namespace, String name, int desiredReplicas) {
        int running = swarm.find(namespace, name).map(s -> runningTasks(s.getId())).orElse(0);

        double cpu = 0;
        try {
            cpu = metrics.averageCpuPercent(namespace, name);
        } catch (Exception e) {
            // A metrics hiccup must not turn a dashboard refresh into a 500 — report 0 and move on.
            log.debug("CPU metrics unavailable for {}/{}: {}", namespace, name, e.getMessage());
        }
        return new DeploymentSnapshot(namespace, name, cpu, desiredReplicas, running);
    }

    private int runningTasks(String serviceId) {
        try {
            return (int) swarm.listTasks(serviceId).stream()
                .filter(t -> t.getStatus() != null && t.getStatus().getState() == TaskState.RUNNING)
                .count();
        } catch (Exception e) {
            log.debug("Task list unavailable for service {}: {}", serviceId, e.getMessage());
            return 0;
        }
    }

    /** Zero for global-mode services, which have no replica count — not an error. */
    private static int desiredReplicas(Service s) {
        if (s.getSpec() == null || s.getSpec().getMode() == null
                || s.getSpec().getMode().getReplicated() == null) {
            return 0;
        }
        return (int) s.getSpec().getMode().getReplicated().getReplicas();
    }

    public record ClusterSnapshot(long activeNodes, long totalNodes, int managedServices,
                                  int runningReplicas, int desiredReplicas,
                                  int deployments, int services, int configMaps, int secrets) { }

    public record DeploymentSnapshot(String namespace, String name, double cpuPercent,
                                     int desiredReplicas, int runningReplicas) { }
}
