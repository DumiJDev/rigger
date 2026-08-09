package io.rigger.manifest.parser;

import io.rigger.core.domain.resource.RiggerManifest;

/**
 * A successfully parsed manifest with its source file reference.
 * The source is used in error messages and audit log entries.
 */
public record ParsedManifest(
        RiggerManifest manifest,
        String source
) {}