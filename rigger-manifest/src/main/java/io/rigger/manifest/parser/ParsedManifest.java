package io.rigger.manifest.parser;

import io.rigger.core.domain.resource.RiggerManifest;

/**
 * A successfully parsed manifest with its source file reference.
 * The source is used in error messages and audit log entries. rawYaml is the single
 * YAML document this manifest was parsed from — needed for schema (re-)validation
 * against a multi-document file, since a bare {@code readTree} only sees one document.
 */
public record ParsedManifest(
        RiggerManifest manifest,
        String source,
        String rawYaml
) {}