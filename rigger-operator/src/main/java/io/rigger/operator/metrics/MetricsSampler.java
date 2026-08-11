package io.rigger.operator.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.DeploymentSpec;
import io.rigger.store.entity.MetricSampleEntity;
import io.rigger.store.repository.EventRepository;
import io.rigger.store.repository.MetricSampleRepository;
import io.rigger.store.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Writes the metric time series the console charts, and bounds the tables that would otherwise
 * grow forever.
 *
 * <p>Sampling server-side rather than letting each browser keep its own window means the history
 * survives a reload, and every operator looking at the cluster sees the same picture.
 *
 * <p>Volume, since a sampler that quietly fills a disk is worse than no charts: at the default 30s
 * interval this writes 9 cluster rows plus 3 per Deployment per round, so 20 Deployments comes to
 * roughly 200k rows a day. With retention at 24h SQLite handles that comfortably, but the two knobs
 * multiply — raising retention to a week while sampling every 5s is 8M rows, and nothing here will
 * warn you.
 */
@Component
public class MetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(MetricsSampler.class);

    private final MetricsCollector       collector;
    private final MetricSampleRepository samples;
    private final ResourceRepository     store;
    private final EventRepository        events;
    private final ObjectMapper           mapper = new ObjectMapper();

    @Value("${rigger.operator.metrics.retention-hours:24}")
    private int retentionHours;

    /**
     * Events are kept far longer than metrics: the activity feed is what someone reads to
     * understand an incident after the fact, while a week-old CPU sample has no audience.
     */
    @Value("${rigger.operator.metrics.event-retention-days:14}")
    private int eventRetentionDays;

    public MetricsSampler(MetricsCollector collector, MetricSampleRepository samples,
                          ResourceRepository store, EventRepository events) {
        this.collector = collector; this.samples = samples;
        this.store = store; this.events = events;
    }

    @Scheduled(fixedDelayString = "${rigger.operator.metrics.sample-interval-seconds:30}000")
    public void sample() {
        var now = Instant.now();
        var batch = new ArrayList<MetricSampleEntity>();

        try {
            var c = collector.cluster();
            add(batch, now, MetricNames.NODES_ACTIVE,          c.activeNodes());
            add(batch, now, MetricNames.NODES_TOTAL,           c.totalNodes());
            add(batch, now, MetricNames.SERVICES_MANAGED,      c.managedServices());
            add(batch, now, MetricNames.REPLICAS_RUNNING,      c.runningReplicas());
            add(batch, now, MetricNames.REPLICAS_DESIRED,      c.desiredReplicas());
            add(batch, now, MetricNames.RESOURCES_DEPLOYMENTS, c.deployments());
            add(batch, now, MetricNames.RESOURCES_SERVICES,    c.services());
            add(batch, now, MetricNames.RESOURCES_CONFIGMAPS,  c.configMaps());
            add(batch, now, MetricNames.RESOURCES_SECRETS,     c.secrets());
        } catch (Exception e) {
            log.warn("Cluster metrics sampling failed: {}", e.getMessage());
        }

        // Concurrently, for the same reason the stats calls inside a single Deployment are: each
        // Deployment costs a round-trip to the Engine, so in sequence a round takes as long as the
        // sum and the configured interval stops meaning anything.
        List<MetricsCollector.DeploymentSnapshot> snapshots;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = store.findAllByKind("Deployment").stream()
                .map(entity -> executor.submit(() -> {
                    // One unreadable spec or one unreachable service must not cost the whole round.
                    try {
                        var spec = mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
                        return collector.deployment(entity.getNamespace(), entity.getName(), spec.replicas());
                    } catch (Exception e) {
                        log.debug("Metrics sampling skipped for {}/{}: {}",
                            entity.getNamespace(), entity.getName(), e.getMessage());
                        return null;
                    }
                }))
                .toList();
            snapshots = futures.stream().map(MetricsSampler::valueOf).filter(Objects::nonNull).toList();
        }

        for (var d : snapshots) {
            add(batch, now, MetricNames.DEPLOYMENT_CPU,              d.namespace(), d.name(), d.cpuPercent());
            add(batch, now, MetricNames.DEPLOYMENT_REPLICAS_RUNNING, d.namespace(), d.name(), d.runningReplicas());
            add(batch, now, MetricNames.DEPLOYMENT_REPLICAS_DESIRED, d.namespace(), d.name(), d.desiredReplicas());
        }

        if (!batch.isEmpty()) {
            try {
                samples.saveAll(batch);
            } catch (Exception e) {
                // Guarded because it sits outside the per-source try blocks above: a single
                // contended write would otherwise discard a whole round of samples that were all
                // collected successfully, and take the exception out to the scheduler. One lost
                // round is a gap in a chart; losing the round silently, or letting it look like a
                // collection failure, is worse.
                log.warn("Could not persist {} metric samples this round: {}", batch.size(), e.getMessage());
            }
        }
    }

    /**
     * Retention, on its own schedule rather than inside {@link #sample()} — at a 30s interval that
     * would issue ~2900 delete statements a day, almost all of them matching nothing.
     *
     * <p>This is also where {@code events} finally gets pruned. That table has grown without bound
     * since it was added: {@link EventRepository#deleteOlderThan} was written for it and never
     * scheduled, so a long-lived server accumulated every reconciliation summary it ever emitted.
     */
    @Scheduled(fixedDelayString = "${rigger.operator.metrics.prune-interval-seconds:3600}000",
               initialDelayString = "${rigger.operator.metrics.prune-initial-delay-seconds:120}000")
    public void prune() {
        try {
            int metricRows = samples.deleteOlderThan(Instant.now().minus(Duration.ofHours(retentionHours)));
            int eventRows  = events.deleteOlderThan(Instant.now().minus(Duration.ofDays(eventRetentionDays)));
            if (metricRows > 0 || eventRows > 0) {
                log.info("Pruned {} metric samples (>{}h) and {} events (>{}d)",
                    metricRows, retentionHours, eventRows, eventRetentionDays);
            }
        } catch (Exception e) {
            log.warn("Retention prune failed, will retry next cycle: {}", e.getMessage());
        }
    }

    /** Unwraps a completed future, treating any failure as "no snapshot this round". */
    private static MetricsCollector.DeploymentSnapshot valueOf(
            Future<MetricsCollector.DeploymentSnapshot> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.debug("Sampling task failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }
    }

    private void add(List<MetricSampleEntity> batch, Instant at, String metric, double value) {
        add(batch, at, metric, MetricNames.CLUSTER_SCOPE, MetricNames.CLUSTER_SCOPE, value);
    }

    private void add(List<MetricSampleEntity> batch, Instant at, String metric,
                     String namespace, String name, double value) {
        batch.add(new MetricSampleEntity(
            UUID.randomUUID().toString(), metric, namespace, name, value, at));
    }
}
