package io.rigger.core.exception;

import java.util.List;

/** Thrown when a manifest YAML fails validation. Contains all violation messages. */
public class ManifestValidationException extends RiggerException {
    private final List<String> violations;

    public ManifestValidationException(List<String> violations) {
        super("Manifest validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() { return violations; }
}
