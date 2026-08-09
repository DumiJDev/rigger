package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for POST /namespaces/{ns}/apply. Contains raw YAML manifest text. */
public record ApplyRequest(
        @JsonProperty("manifest") String manifest,
        @JsonProperty("dryRun")   boolean dryRun
) {}
