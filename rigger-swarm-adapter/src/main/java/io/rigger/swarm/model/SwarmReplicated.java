package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Replicated mode: desired number of task replicas. */
public record SwarmReplicated(@JsonProperty("Replicas") long replicas) {}
