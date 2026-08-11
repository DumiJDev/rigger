package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.rigger.manifest.converter.ComposeConverter;
import java.util.List;

/**
 * Result of converting docker-compose input to {@code rigger.io/v1} YAML.
 *
 * <p>{@code yaml} is the whole point: a multi-document manifest the caller can redirect to a file
 * and apply. {@code issues} is the other half — everything Compose expressed that Rigger cannot,
 * each naming its Compose path. {@code blocked} says whether {@code apply} would refuse this input
 * (i.e. at least one issue is an ERROR), so a caller can present the choice before it happens.
 *
 * @param yaml      Generated multi-document rigger.io/v1 YAML. Never persisted anywhere.
 * @param resources One entry per generated manifest, for a compact summary.
 * @param issues    Everything the converter did not carry across literally.
 * @param blocked   True when at least one issue is an ERROR.
 */
public record ConvertResponse(
        @JsonProperty("yaml") String yaml,
        @JsonProperty("resources") List<ConvertedResource> resources,
        @JsonProperty("issues") List<ComposeIssue> issues,
        @JsonProperty("blocked") boolean blocked
) {

    public record ConvertedResource(
            @JsonProperty("kind") String kind,
            @JsonProperty("name") String name,
            @JsonProperty("namespace") String namespace
    ) {}

    /**
     * Wire form of {@link ComposeConverter.Issue}. The converter's own record could be serialised
     * directly, but a DTO keeps the JSON field names owned by the API module rather than moving
     * whenever the converter's internals are renamed.
     */
    public record ComposeIssue(
            @JsonProperty("severity") String severity,
            @JsonProperty("path") String path,
            @JsonProperty("message") String message
    ) {
        public static ComposeIssue from(ComposeConverter.Issue issue) {
            return new ComposeIssue(issue.severity().name(), issue.path(), issue.message());
        }
    }

    public static ConvertResponse from(String yaml, ComposeConverter.Conversion conversion) {
        return new ConvertResponse(
            yaml,
            conversion.manifests().stream()
                .map(pm -> new ConvertedResource(pm.manifest().kind(),
                    pm.manifest().metadata().name(), pm.manifest().metadata().namespace()))
                .toList(),
            conversion.issues().stream().map(ComposeIssue::from).toList(),
            conversion.hasErrors());
    }
}
