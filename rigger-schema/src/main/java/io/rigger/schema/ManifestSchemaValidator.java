package io.rigger.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.*;
import io.rigger.core.exception.ManifestValidationException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates manifest YAML/JSON against the embedded JSON Schema definitions.
 *
 * <p>This runs <em>before</em> the domain-level validation in {@code rigger-manifest}.
 * It catches structural errors (wrong field types, missing required fields, bad enum values)
 * and returns all violations at once — no fail-fast behaviour.
 *
 * <p>Usage:
 * <pre>
 * var violations = validator.validate("Deployment", yamlString);
 * if (!violations.isEmpty()) throw new ManifestValidationException(violations);
 * </pre>
 */
public class ManifestSchemaValidator {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * Validates a YAML manifest string against its kind's JSON Schema.
     *
     * @param kind       Resource kind (Deployment, Service, etc.).
     * @param yamlContent Raw YAML content of the manifest.
     * @return List of violation messages. Empty if valid.
     */
    public List<String> validate(String kind, String yamlContent) {
        var schemaUrl = SchemaRegistry.schemaUrlFor(kind);
        if (schemaUrl.isEmpty()) {
            return List.of("No schema registered for kind: " + kind);
        }

        try {
            JsonNode document = yaml.readTree(yamlContent);
            JsonSchema schema = schemaFactory.getSchema(schemaUrl.get().toURI());
            Set<ValidationMessage> messages = schema.validate(document);
            return messages.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("Schema validation error: " + e.getMessage());
        }
    }

    /**
     * Validates and throws if there are violations.
     *
     * @throws ManifestValidationException with all violation messages.
     */
    public void validateOrThrow(String kind, String yamlContent) {
        List<String> violations = validate(kind, yamlContent);
        if (!violations.isEmpty()) {
            throw new ManifestValidationException(violations);
        }
    }
}
