package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /api/v1/namespaces/{ns}/convert}: the docker-compose document to translate.
 *
 * <p>Separate from {@link ApplyRequest} even though the field is the same, because
 * {@code ApplyRequest.dryRun} has no meaning here — converting never persists anything, so a
 * caller passing {@code dryRun: false} would be told, falsely, that something was about to happen.
 *
 * @param content Raw docker-compose YAML.
 */
public record ConvertRequest(
        @JsonProperty("content") String content
) {}
