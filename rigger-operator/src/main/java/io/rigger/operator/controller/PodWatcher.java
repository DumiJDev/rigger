package io.rigger.operator.controller;

import com.github.dockerjava.api.model.Task;
import io.rigger.events.bus.RiggerEventBus;
import io.rigger.events.model.PodStateChangedEvent;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches pod (Swarm task) membership and state per managed Deployment, centrally, so the pods
 * SSE stream doesn't need each open browser connection polling Docker on its own — that would
 * multiply {@code listTasks} calls by however many tabs are open.
 *
 * <p>The last-seen {@code (taskId -> state)} snapshot per Swarm service is kept in memory only —
 * this is a live signal for "something changed, refetch", not a durable record, so it doesn't need
 * a table or a Flyway migration. A snapshot for a service that disappears (Deployment deleted) is
 * dropped so this map doesn't grow without bound.
 */
@Component
public class PodWatcher {

    private static final Logger log = LoggerFactory.getLogger(PodWatcher.class);

    private final ServiceAdapter  swarm;
    private final RiggerEventBus  eventBus;
    private final Map<String, Map<String, String>> lastSeen = new ConcurrentHashMap<>();

    public PodWatcher(ServiceAdapter swarm, RiggerEventBus eventBus) {
        this.swarm = swarm;
        this.eventBus = eventBus;
    }

    @Scheduled(fixedDelayString = "${rigger.operator.pods.watch-interval-seconds:15}000")
    public void watch() {
        var managed = swarm.listManaged();
        var seenServiceIds = new java.util.HashSet<String>();

        for (var svc : managed) {
            if (svc.getSpec() == null || svc.getSpec().getLabels() == null) continue;
            String namespace = svc.getSpec().getLabels().get("rigger.io/namespace");
            String name      = svc.getSpec().getLabels().get("rigger.io/name");
            if (namespace == null || name == null) continue;

            seenServiceIds.add(svc.getId());
            try {
                var current = currentStateOf(swarm.listTasks(svc.getId()));
                var previous = lastSeen.put(svc.getId(), current);
                if (previous != null && !previous.equals(current)) {
                    eventBus.publish(new PodStateChangedEvent(namespace, name));
                } else if (previous == null && !current.isEmpty()) {
                    // First sighting of a service with running tasks — not a change worth telling
                    // an already-open stream about, since its own initial load already saw them.
                    log.debug("PodWatcher: baseline captured for {}/{} ({} tasks)", namespace, name, current.size());
                }
            } catch (Exception e) {
                log.warn("PodWatcher: failed to list tasks for {}/{} ({}): {}", namespace, name, svc.getId(), e.getMessage());
            }
        }

        lastSeen.keySet().retainAll(seenServiceIds);
    }

    private static Map<String, String> currentStateOf(java.util.List<Task> tasks) {
        var byId = new HashMap<String, String>();
        for (var task : tasks) {
            var status = task.getStatus();
            byId.put(task.getId(), status != null && status.getState() != null ? status.getState().name() : "UNKNOWN");
        }
        return byId;
    }
}
