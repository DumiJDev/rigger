package io.rigger.api.stream;

import io.rigger.core.domain.resource.ResourceKind;
import io.rigger.events.model.PodStateChangedEvent;
import io.rigger.events.model.ResourceAppliedEvent;
import io.rigger.events.model.ResourceDeletedEvent;
import io.rigger.events.model.ResourceScaledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans out "something changed" pings to open topology/pods SSE connections, one list per
 * namespace per channel. Deliberately carries no resource payload: a client that receives a ping
 * just re-runs the same fetch it already does on its polling tick, so there is no partial-state
 * diff/merge logic to get wrong on the browser side — see {@code podLogsSse} for the sibling
 * pattern this follows (SseEmitter, not hand-rolled {@code data:} framing).
 *
 * <p>Every endpoint that opens a connection here is namespace-scoped and authorized once at
 * connect time by its controller, matching {@code RbacPolicyEngine}'s per-namespace model — this
 * hub does not re-check authorization per event.
 */
@Component
public class NamespaceSseHub {

    private static final Logger log = LoggerFactory.getLogger(NamespaceSseHub.class);

    private final Map<String, List<SseEmitter>> topology = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> pods = new ConcurrentHashMap<>();

    public SseEmitter subscribeTopology(String namespace) {
        return subscribe(topology, namespace);
    }

    public SseEmitter subscribePods(String namespace) {
        return subscribe(pods, namespace);
    }

    private SseEmitter subscribe(Map<String, List<SseEmitter>> byNamespace, String namespace) {
        var emitter = new SseEmitter(0L);
        var list = byNamespace.computeIfAbsent(namespace, ns -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @EventListener
    public void onApplied(ResourceAppliedEvent e) {
        ping(e.resource().namespace(), e.type());
        if (e.resource().kind() == ResourceKind.DEPLOYMENT) pingPods(e.resource().namespace(), e.type());
    }

    @EventListener
    public void onDeleted(ResourceDeletedEvent e) {
        ping(e.resource().namespace(), e.type());
        if (e.resource().kind() == ResourceKind.DEPLOYMENT) pingPods(e.resource().namespace(), e.type());
    }

    @EventListener
    public void onScaled(ResourceScaledEvent e) {
        ping(e.resource().namespace(), e.type());
        pingPods(e.resource().namespace(), e.type());
    }

    @EventListener
    public void onPodStateChanged(PodStateChangedEvent e) {
        pingPods(e.namespace(), e.type());
    }

    private void ping(String namespace, String type) {
        send(topology.get(namespace), type);
    }

    private void pingPods(String namespace, String type) {
        send(pods.get(namespace), type);
    }

    private void send(List<SseEmitter> emitters, String type) {
        if (emitters == null || emitters.isEmpty()) return;
        for (var emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().data(type));
            } catch (Exception e) {
                // A closed tab arrives here as a broken pipe — routine, not a fault. onError above
                // already schedules removal; nothing further to do here.
                log.debug("Dropping SSE emitter after failed send: {}", e.getMessage());
            }
        }
    }
}
