package io.rigger.events.bus;

import io.rigger.events.model.*;

/**
 * Marker interface for components that consume Rigger events.
 * Implementations use Spring {@code @EventListener} on individual methods.
 *
 * <p>Example implementation:
 * <pre>
 * {@literal @}Component
 * public class DeploymentEventHandler implements RiggerEventListener {
 *
 *     {@literal @}EventListener
 *     public void onApplied(ResourceAppliedEvent event) {
 *         // update UI state, send notification, etc.
 *     }
 * }
 * </pre>
 */
public interface RiggerEventListener {}