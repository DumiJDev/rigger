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
import java.util.UUID;

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

        for (var entity : store.findAllByKind("Deployment")) {
            try {
                var spec = mapper.readValue(entity.getSpecJson(), DeploymentSpec.class);
                var d = collector.deployment(entity.getNamespace(), entity.getName(), spec.replicas());
                add(batch, now, MetricNames.DEPLOYMENT_CPU,              d.namespace(), d.name(), d.cpuPercent());
                add(batch, now, MetricNames.DEPLOYMENT_REPLICAS_RUNNING, d.namespace(), d.name(), d.runningReplicas());
                add(batch, now, MetricNames.DEPLOYMENT_REPLICAS_DESIRED, d.namespace(), d.name(), d.desiredReplicas());
            } catch (Exception e) {
                // One unreadable spec or one unreachable service must not cost the whole round.
                log.debug("Metrics sampling skipped for {}/{}: {}",
                    entity.getNamespace(), entity.getName(), e.getMessage());
            }
        }

        if (!batch.isEmpty()) {
            samples.saveAll(batch);
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

    private void add(List<MetricSampleEntity> batch, Instant at, String metric, double value) {
        add(batch, at, metric, MetricNames.CLUSTER_SCOPE, MetricNames.CLUSTER_SCOPE, value);
    }

    private void add(List<MetricSampleEntity> batch, Instant at, String metric,
                     String namespace, String name, double value) {
        batch.add(new MetricSampleEntity(
            UUID.randomUUID().toString(), metric, namespace, name, value, at));
    }
}
