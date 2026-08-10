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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls per-container CPU usage from the Docker Engine API ({@code StatsCmd}) and averages it
 * across every running task of a Deployment's Swarm service — no Prometheus dependency needed.
 *
 * <p>One blocking, non-streaming stats request per running container. Measured against a real
 * Engine, each takes about 2 seconds — the Docker API needs two samples to compute a CPU delta, so
 * the wait is inherent and not something a faster client would avoid.
 *
 * <p>Which is why the requests are issued <strong>concurrently on virtual threads</strong>. Run in
 * sequence they cost 2s × task count, and that silently broke the schedules built on top: a sampler
 * configured for 5s ran every 17s with six tasks, because {@code fixedDelay} counts from the end of
 * the previous run. A 30-task Deployment would have turned a 30s cycle into 90s. Blocking IO fanned
 * out over virtual threads is the case they exist for, and it makes the cost of a cycle the cost of
 * its slowest single call rather than the sum of all of them.
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

        var containerIds = serviceAdapter.listTasks(svcOpt.get().getId()).stream()
            .filter(t -> t.getStatus() != null && t.getStatus().getState() == TaskState.RUNNING)
            .map(Task::getStatus)
            .filter(s -> s.getContainerStatus() != null && s.getContainerStatus().getContainerID() != null)
            .map(s -> s.getContainerStatus().getContainerID())
            .toList();

        if (containerIds.isEmpty()) return 0;

        // A fresh executor per call rather than a shared pool: virtual threads are cheap to create,
        // and this way there is no pool to size, no queue to starve, and nothing left running
        // between cycles.
        List<Double> samples;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = containerIds.stream()
                .map(id -> executor.submit(() -> containerCpuPercent(id)))
                .toList();
            samples = futures.stream().map(DockerStatsMetricsSource::valueOf).filter(Objects::nonNull).toList();
        }

        // Containers that failed to report are excluded rather than counted as zero: averaging in a
        // zero for an unreadable container would report a busy Deployment as idle, which for the HPA
        // means scaling down exactly when it should not.
        return samples.isEmpty() ? 0 : samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Unwraps a completed future, treating any failure as "no sample" — same as an unreadable container. */
    private static Double valueOf(Future<Double> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.debug("Stats task failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }
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
