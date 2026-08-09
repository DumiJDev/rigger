package io.rigger.events.model;

import io.rigger.core.domain.cluster.NodeRole;

/** Fired when a new node is successfully provisioned and joins the Swarm. */
public final class NodeAddedEvent extends RiggerEvent {
    private final String nodeName;
    private final String ip;
    private final NodeRole role;

    public NodeAddedEvent(String nodeName, String ip, NodeRole role) {
        super();
        this.nodeName = nodeName; this.ip = ip; this.role = role;
    }

    @Override public String type() { return "node.added"; }
    public String nodeName() { return nodeName; }
    public String ip() { return ip; }
    public NodeRole role() { return role; }
}