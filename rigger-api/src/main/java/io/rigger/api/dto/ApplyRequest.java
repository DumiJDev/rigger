package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for POST /namespaces/{ns}/apply. Contains raw YAML manifest text. */
public record ApplyRequest(String manifest, boolean dryRun) {

    /**
     * Accepts {@code dryRun} as a nullable box so that omitting it means false.
     *
     * <p>With a plain {@code boolean} record component, a body of just
     * {@code {"manifest": "..."}} failed deserialisation — Jackson refuses to map the absent value
     * onto a primitive — and came back as a 500. So an optional field was effectively mandatory,
     * and reported as a server fault when left out.
     */
    @JsonCreator
    public static ApplyRequest of(@JsonProperty("manifest") String manifest,
                                  @JsonProperty("dryRun") Boolean dryRun) {
        return new ApplyRequest(manifest, Boolean.TRUE.equals(dryRun));
    }
}
