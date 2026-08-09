package io.rigger.events.model;

/** Fired when a node drain completes and the node has left the Swarm. */
public final class NodeDrainedEvent extends RiggerEvent {
    private final String nodeName;

    public NodeDrainedEvent(String nodeName) { super(); this.nodeName = nodeName; }

    @Override public String type() { return "node.drained"; }
    public String nodeName() { return nodeName; }
}