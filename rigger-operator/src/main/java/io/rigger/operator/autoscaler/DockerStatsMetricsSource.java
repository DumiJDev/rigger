package io.rigger.operator.autoscaler;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskState;
import io.rigger.swarm.adapter.ServiceAdapter;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls per-container CPU usage from the Docker Engine API ({@code StatsCmd}) and averages it
 * across every running task of a Deployment's Swarm service — no Prometheus dependency needed.
 *
 * <p>Each call issues one blocking, non-streaming stats request per running container, so cost
 * scales with task count; {@link HpaController} already limits how often this runs (default
 * every 30s), which keeps this within reasonable bounds for typical cluster sizes. Clusters with
 * very many tasks per Deployment will feel this as added latency on each HPA cycle — a caveat
 * worth knowing rather than hiding.
 */
@Component
public class DockerStatsMetricsSource implements MetricsSource {

    private static final Logger log = LoggerFactory.getLogger(DockerStatsMetricsSource.class);
    private static final long STATS_TIMEOUT_SECONDS = 5;

    private final ServiceAdapter serviceAdapter;
    private final DockerClientFactory factory;

    public DockerStatsMetricsSource(ServiceAdapter serviceAdapter, DockerClientFactory factory) {
        this.serviceAdapter = serviceAdapter;
        this.factory = factory;
    }

    @Override
    public double averageCpuPercent(String namespace, String name) {
        var svcOpt = serviceAdapter.find(namespace, name);
        if (svcOpt.isEmpty()) return 0;

        var tasks = serviceAdapter.listTasks(svcOpt.get().getId());
        double total = 0;
        int counted = 0;

        for (Task task : tasks) {
            if (task.getStatus() == null || task.getStatus().getState() != TaskState.RUNNING) continue;
            var containerStatus = task.getStatus().getContainerStatus();
            if (containerStatus == null || containerStatus.getContainerID() == null) continue;

            Double pct = containerCpuPercent(containerStatus.getContainerID());
            if (pct != null) {
                total += pct;
                counted++;
            }
        }

        return counted == 0 ? 0 : total / counted;
    }

    private Double containerCpuPercent(String containerId) {
        try {
            var ref = new AtomicReference<Statistics>();
            var callback = new ResultCallback.Adapter<Statistics>() {
                @Override
                public void onNext(Statistics stats) {
                    ref.compareAndSet(null, stats);
                }
            };
            factory.get().statsCmd(containerId).withNoStream(true).exec(callback)
                .awaitCompletion(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            var stats = ref.get();
            if (stats == null || stats.getCpuStats() == null || stats.getPreCpuStats() == null) return null;

            var cpu    = stats.getCpuStats();
            var preCpu = stats.getPreCpuStats();
            if (cpu.getCpuUsage() == null || preCpu.getCpuUsage() == null) return null;
            if (cpu.getCpuUsage().getTotalUsage() == null || preCpu.getCpuUsage().getTotalUsage() == null) return null;
            if (cpu.getSystemCpuUsage() == null || preCpu.getSystemCpuUsage() == null) return null;

            long cpuDelta    = cpu.getCpuUsage().getTotalUsage() - preCpu.getCpuUsage().getTotalUsage();
            long systemDelta = cpu.getSystemCpuUsage() - preCpu.getSystemCpuUsage();
            if (systemDelta <= 0 || cpuDelta < 0) return null;

            long onlineCpus = cpu.getOnlineCpus() != null ? cpu.getOnlineCpus()
                : (cpu.getCpuUsage().getPercpuUsage() != null ? cpu.getCpuUsage().getPercpuUsage().size() : 1);

            return ((double) cpuDelta / systemDelta) * onlineCpus * 100.0;
        } catch (Exception e) {
            log.debug("Failed to read stats for container {}: {}", containerId, e.getMessage());
            return null;
        }
    }
}
