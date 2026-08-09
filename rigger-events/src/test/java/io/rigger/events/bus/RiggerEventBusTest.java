package io.rigger.events.bus;

import io.rigger.core.domain.cluster.NodeRole;
import io.rigger.core.domain.resource.ResourceKind;
import io.rigger.core.domain.resource.ResourceRef;
import io.rigger.events.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {RiggerEventBus.class, RiggerEventBusTest.TestListener.class})
class RiggerEventBusTest {

    @Autowired RiggerEventBus eventBus;
    @Autowired TestListener listener;

    @Test
    void publishResourceApplied_listenerReceivesIt() {
        var ref = new ResourceRef(ResourceKind.DEPLOYMENT, "prod", "payments-api");
        eventBus.publish(new ResourceAppliedEvent(ref, "alice", true));
        assertEquals(1, listener.received.size());
        assertInstanceOf(ResourceAppliedEvent.class, listener.received.get(0));
    }

    @Test
    void publishNodeAdded_listenerReceivesIt() {
        eventBus.publish(new NodeAddedEvent("worker-02", "10.0.0.22", NodeRole.WORKER));
        assertTrue(listener.received.stream().anyMatch(e -> e instanceof NodeAddedEvent));
    }

    @Test
    void eachEvent_hasUniqueId() {
        var ref = new ResourceRef(ResourceKind.DEPLOYMENT, "prod", "app");
        eventBus.publish(new ResourceAppliedEvent(ref, "bob", false));
        eventBus.publish(new ResourceAppliedEvent(ref, "bob", false));
        var ids = listener.received.stream().map(RiggerEvent::eventId).distinct().toList();
        assertEquals(listener.received.size(), ids.size());
    }

    @Component
    static class TestListener implements RiggerEventListener {
        final List<RiggerEvent> received = new ArrayList<>();

        @EventListener
        void on(RiggerEvent event) { received.add(event); }
    }
}