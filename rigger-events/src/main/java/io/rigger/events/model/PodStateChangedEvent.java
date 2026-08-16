package io.rigger.events.model;

/**
 * Fired when the set of pods (Swarm tasks) or their states for a Deployment
 * changes between two watch cycles — a task started, stopped, or transitioned
 * state. Carries no task-level detail; consumers (the pods SSE stream) treat
 * it as a signal to refetch, not a diff to apply.
 */
public final class PodStateChangedEvent extends RiggerEvent {
    private final String namespace;
    private final String deploymentName;

    public PodStateChangedEvent(String namespace, String deploymentName) {
        super();
        this.namespace = namespace;
        this.deploymentName = deploymentName;
    }

    @Override public String type() { return "pod.state-changed"; }
    public String namespace() { return namespace; }
    public String deploymentName() { return deploymentName; }
}
