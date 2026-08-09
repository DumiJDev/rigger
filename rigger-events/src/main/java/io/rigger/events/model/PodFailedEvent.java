package io.rigger.events.model;

/** Fired when a pod (Swarm task) enters a failed state. */
public final class PodFailedEvent extends RiggerEvent {
    private final String podName;
    private final String namespace;
    private final String nodeName;
    private final String exitCode;
    private final String message;

    public PodFailedEvent(String podName, String namespace, String nodeName, String exitCode, String message) {
        super();
        this.podName = podName; this.namespace = namespace;
        this.nodeName = nodeName; this.exitCode = exitCode; this.message = message;
    }

    @Override public String type() { return "pod.failed"; }
    public String podName() { return podName; }
    public String namespace() { return namespace; }
    public String nodeName() { return nodeName; }
    public String exitCode() { return exitCode; }
    public String message() { return message; }
}