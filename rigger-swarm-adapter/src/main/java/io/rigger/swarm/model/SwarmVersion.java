package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Docker object version (used for optimistic concurrency on service updates). */
public record SwarmVersion(@JsonProperty("Index") long index) {}
