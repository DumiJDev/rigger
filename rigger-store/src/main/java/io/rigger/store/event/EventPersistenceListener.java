package io.rigger.store.event;

import io.rigger.events.model.*;
import io.rigger.store.entity.EventEntity;
import io.rigger.store.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Persists {@link RiggerEvent}s published on the in-memory bus so the console's activity feed
 * survives a restart.
 *
 * <p>Reconciliation-cycle events are deliberately dropped unless something actually happened: the
 * loop fires every 15 seconds, so recording quiet cycles would bury real events under thousands of
 * "nothing changed" rows.
 *
 * <p>Persisting an event must never break the operation that produced it, so failures here are
 * logged and swallowed.
 */
@Component
public class EventPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(EventPersistenceListener.class);

    private final EventRepository repo;

    public EventPersistenceListener(EventRepository repo) {
        this.repo = repo;
    }

    @EventListener
    public void onEvent(RiggerEvent event) {
        try {
            var entity = toEntity(event);
            if (entity != null) repo.save(entity);
        } catch (Exception e) {
            log.warn("Could not persist event {}: {}", event.type(), e.getMessage());
        }
    }

    private EventEntity toEntity(RiggerEvent e) {
        return switch (e) {
            case ResourceAppliedEvent ev -> build(ev, ev.resource().kind().name(), ev.resource().name(),
                ev.resource().namespace(), ev.appliedBy(),
                ev.isCreated() ? "Resource created" : "Resource updated");

            case ResourceDeletedEvent ev -> build(ev, ev.resource().kind().name(), ev.resource().name(),
                ev.resource().namespace(), ev.deletedBy(), "Resource deleted");

            case ResourceScaledEvent ev -> build(ev, ev.resource().kind().name(), ev.resource().name(),
                ev.resource().namespace(), ev.reason(),
                "Scaled %d -> %d replicas".formatted(ev.previousReplicas(), ev.newReplicas()));

            case HpaScaledEvent ev -> build(ev, ev.deployment().kind().name(), ev.deployment().name(),
                ev.deployment().namespace(), "hpa",
                "Autoscaled %d -> %d replicas (cpu %.0f%%, target %d%%)".formatted(
                    ev.previousReplicas(), ev.newReplicas(), ev.currentCpuPercent(), ev.targetCpuPercent()));

            case PodFailedEvent ev -> build(ev, "Pod", ev.podName(), ev.namespace(), ev.nodeName(),
                "Pod failed (exit %s): %s".formatted(ev.exitCode(), ev.message()));

            // Backs the pods SSE stream (NamespaceSseHub, rigger-api), not the activity feed: it
            // fires on ordinary rolling updates and scaling, not just noteworthy events, and would
            // bury the real ones the same way a quiet reconciliation cycle would.
            case PodStateChangedEvent ev -> null;

            case NodeAddedEvent ev -> build(ev, "Node", ev.nodeName(), null, null,
                "Node joined as %s (%s)".formatted(ev.role(), ev.ip()));

            case NodeDrainedEvent ev -> build(ev, "Node", ev.nodeName(), null, null, "Node drained");

            case GitOpsSyncEvent ev -> build(ev, "GitOps", ev.repositoryUrl(), null, "gitops",
                ev.isSuccess()
                    ? "Synced %s (%d manifests)".formatted(ev.commitHash(), ev.manifestsApplied())
                    : "Sync failed at %s: %s".formatted(ev.commitHash(), ev.errorMessage()));

            // Only worth a row when the cycle actually did something or failed.
            case ReconciliationEvent ev -> {
                int changes = ev.created() + ev.updated() + ev.deleted();
                yield (changes == 0 && ev.errors() == 0) ? null
                    : build(ev, null, null, null, "operator",
                        "Reconciled %d change(s), %d error(s) in %dms".formatted(
                            changes, ev.errors(), ev.durationMs()));
            }
        };
    }

    private EventEntity build(RiggerEvent e, String kind, String name,
                              String namespace, String actor, String message) {
        return new EventEntity(e.eventId(), e.type(), kind, name, namespace, actor, message, e.occurredAt());
    }
}
