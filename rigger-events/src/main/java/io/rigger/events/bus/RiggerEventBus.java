package io.rigger.events.bus;

import io.rigger.events.model.RiggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Central event bus for all internal Rigger events.
 * Wraps Spring's ApplicationEventPublisher to provide a typed,
 * domain-specific API with automatic logging.
 *
 * <p>Events are synchronous by default (same thread as publisher).
 * Consumers that need async processing should use {@code @Async} on
 * their {@code @EventListener} methods.
 *
 * <p>Usage:
 * <pre>
 * eventBus.publish(new ResourceAppliedEvent(ref, "alice", true));
 * </pre>
 */
@Component
public class RiggerEventBus {

    private static final Logger log = LoggerFactory.getLogger(RiggerEventBus.class);
    private final ApplicationEventPublisher publisher;

    public RiggerEventBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publishes an event to all registered listeners.
     * Never throws — exceptions in listeners are caught and logged.
     */
    public void publish(RiggerEvent event) {
        log.debug("Publishing event: type={} id={}", event.type(), event.eventId());
        try {
            publisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Event listener threw an exception for event type={}: {}", event.type(), e.getMessage(), e);
        }
    }
}