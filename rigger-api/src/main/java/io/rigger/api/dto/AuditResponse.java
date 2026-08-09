package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.rigger.core.domain.security.*;
import java.time.Instant;

/** Single audit log entry returned by GET /audit. */
public record AuditResponse(
        @JsonProperty("id")            String id,
        @JsonProperty("identityName")  String identityName,
        @JsonProperty("identityRole")  String identityRole,
        @JsonProperty("action")        String action,
        @JsonProperty("resourceKind")  String resourceKind,
        @JsonProperty("resourceName")  String resourceName,
        @JsonProperty("namespace")     String namespace,
        @JsonProperty("sourceIp")      String sourceIp,
        @JsonProperty("timestamp")     Instant timestamp,
        @JsonProperty("result")        String result,
        @JsonProperty("errorMessage")  String errorMessage
) {}
