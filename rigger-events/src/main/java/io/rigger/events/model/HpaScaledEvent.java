package io.rigger.events.model;

import io.rigger.core.domain.resource.ResourceRef;

/** Fired when the HPA controller changes the replica count of a Deployment. */
public final class HpaScaledEvent extends RiggerEvent {
    private final ResourceRef deployment;
    private final int previousReplicas;
    private final int newReplicas;
    private final double currentCpuPercent;
    private final int targetCpuPercent;

    public HpaScaledEvent(ResourceRef deployment, int prev, int next, double cpu, int target) {
        super();
        this.deployment = deployment; this.previousReplicas = prev; this.newReplicas = next;
        this.currentCpuPercent = cpu; this.targetCpuPercent = target;
    }

    @Override public String type() { return "hpa.scaled"; }
    public ResourceRef deployment() { return deployment; }
    public int previousReplicas() { return previousReplicas; }
    public int newReplicas() { return newReplicas; }
    public double currentCpuPercent() { return currentCpuPercent; }
    public int targetCpuPercent() { return targetCpuPercent; }
}