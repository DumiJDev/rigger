package io.rigger.core.domain.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A single immutable audit log entry.
 * Stored append-only — deletion via API is not permitted.
 * Secret values are NEVER included in beforeState or afterState.
 *
 * @param id           UUID v7 (time-sortable).
 * @param identityName Who performed the action (identity name, not ID).
 * @param identityRole Role of the actor at the time of the action.
 * @param action       What was done.
 * @param resourceKind Kind of resource affected (e.g. "Deployment").
 * @param resourceName Name of the resource.
 * @param namespace    Namespace of the resource.
 * @param sourceIp     IP address of the request originator.
 * @param timestamp    When the action occurred.
 * @param result       SUCCESS | DENIED | ERROR.
 * @param errorMessage Error message if result is ERROR. Null otherwise.
 * @param beforeState  JSON of previous state (null for CREATE). No secret values.
 * @param afterState   JSON of new state (null for DELETE). No secret values.
 */
public record AuditEntry(
        @JsonProperty("id") String id,
        @JsonProperty("identityName") String identityName,
        @JsonProperty("identityRole") String identityRole,
        @JsonProperty("action") AuditAction action,
        @JsonProperty("resourceKind") String resourceKind,
        @JsonProperty("resourceName") String resourceName,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("sourceIp") String sourceIp,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("result") AuditResult result,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("beforeState") String beforeState,
        @JsonProperty("afterState") String afterState
) {}
