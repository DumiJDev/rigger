package io.rigger.manifest.validator;

import io.rigger.core.domain.resource.*;
import io.rigger.core.exception.ManifestValidationException;
import io.rigger.manifest.parser.ParsedManifest;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates a parsed manifest against Rigger rules.
 * Collects all violations before throwing — callers see the full list.
 *
 * <p>Rules validated:
 * <ul>
 *   <li>metadata.name: required, alphanumeric + dashes, max 63 chars</li>
 *   <li>metadata.namespace: required, same pattern as name</li>
 *   <li>Deployment: image must be non-blank; replicas >= 0</li>
 *   <li>HPA: minReplicas <= maxReplicas</li>
 *   <li>Service: selector and ports required</li>
 *   <li>Secret: data or vaultRef required</li>
 * </ul>
 */
@Component
public class ManifestValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$|^[a-z0-9]$");

    public void validate(ParsedManifest parsed) {
        validate(parsed.manifest());
    }

    public void validate(RiggerManifest manifest) {
        var violations = new ArrayList<String>();
        validateMeta(manifest.metadata(), violations);
        validateSpec(manifest.kind(), manifest.spec(), violations);
        if (!violations.isEmpty()) throw new ManifestValidationException(violations);
    }

    private void validateMeta(ObjectMeta meta, List<String> v) {
        if (meta.name() == null || meta.name().isBlank()) {
            v.add("metadata.name is required");
        } else if (!NAME_PATTERN.matcher(meta.name()).matches()) {
            v.add("metadata.name '" + meta.name() + "' must be lowercase alphanumeric with dashes, max 63 chars");
        }
        if (meta.namespace() == null || meta.namespace().isBlank()) {
            v.add("metadata.namespace is required — Rigger does not support implicit namespaces");
        } else if (!NAME_PATTERN.matcher(meta.namespace()).matches()) {
            v.add("metadata.namespace '" + meta.namespace() + "' must be lowercase alphanumeric with dashes");
        }
    }

    private void validateSpec(String kind, Object spec, List<String> v) {
        switch (kind) {
            case "Deployment" -> validateDeployment((DeploymentSpec) spec, v);
            case "Service"    -> validateService((ServiceSpec) spec, v);
            case "Secret"     -> validateSecret((SecretSpec) spec, v);
            // ConfigMap has no additional constraints beyond its record constructor
        }
    }

    private void validateDeployment(DeploymentSpec spec, List<String> v) {
        if (spec == null) { v.add("spec is required for Deployment"); return; }
        if (spec.image() == null || spec.image().isBlank()) v.add("spec.image is required");
        if (spec.replicas() < 0) v.add("spec.replicas must be >= 0");
        if (spec.hpa() != null) {
            var hpa = spec.hpa();
            if (hpa.minReplicas() < 1) v.add("spec.hpa.minReplicas must be >= 1");
            if (hpa.maxReplicas() < hpa.minReplicas())
                v.add("spec.hpa.maxReplicas must be >= minReplicas");
            if (hpa.targetCPUUtilizationPercentage() < 1 || hpa.targetCPUUtilizationPercentage() > 100)
                v.add("spec.hpa.targetCPUUtilizationPercentage must be between 1 and 100");
        }
    }

    private void validateService(ServiceSpec spec, List<String> v) {
        if (spec == null) { v.add("spec is required for Service"); return; }
        if (spec.selector() == null || spec.selector().isEmpty()) v.add("spec.selector is required for Service");
        if (spec.ports() == null || spec.ports().isEmpty()) v.add("spec.ports is required for Service");
    }

    private void validateSecret(SecretSpec spec, List<String> v) {
        if (spec == null) { v.add("spec is required for Secret"); return; }
        boolean hasData = spec.data() != null && !spec.data().isEmpty();
        boolean hasVault = spec.vaultRef() != null && !spec.vaultRef().isBlank();
        if (!hasData && !hasVault) v.add("spec.data or spec.vaultRef is required for Secret");
    }
}