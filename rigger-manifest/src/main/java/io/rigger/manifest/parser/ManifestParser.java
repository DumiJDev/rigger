package io.rigger.manifest.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.rigger.core.domain.resource.*;
import io.rigger.core.exception.ManifestValidationException;
import io.rigger.manifest.validator.ManifestValidator;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parses rigger.io/v1 YAML manifest files into typed domain objects.
 *
 * <p>Supports:
 * <ul>
 *   <li>Single-document YAML files</li>
 *   <li>Multi-document YAML files (separated by ---)</li>
 *   <li>Directories (all *.yaml and *.yml files)</li>
 * </ul>
 *
 * <p>Unknown kinds are rejected at parse time. Secret values are base64-decoded
 * from the YAML but never written to logs.
 */
@Component
public class ManifestParser {

    private final ObjectMapper yaml;
    private final ManifestValidator validator;
    private final KindRegistry kindRegistry;

    public ManifestParser(ManifestValidator validator, KindRegistry kindRegistry) {
        this.yaml = new ObjectMapper(new YAMLFactory());
        this.yaml.findAndRegisterModules();
        this.validator = validator;
        this.kindRegistry = kindRegistry;
    }

    /**
     * Parses a single YAML file. Supports multi-document files (---).
     *
     * @param path Path to the YAML file.
     * @return List of parsed manifests (one per YAML document).
     * @throws ManifestValidationException if any document fails validation.
     */
    public List<ParsedManifest> parseFile(Path path) throws IOException {
        String content = Files.readString(path);
        return parseString(content, path.toString());
    }

    /**
     * Parses all *.yaml and *.yml files in a directory (non-recursive).
     */
    public List<ParsedManifest> parseDirectory(Path dir) throws IOException {
        var results = new ArrayList<ParsedManifest>();
        try (var stream = Files.list(dir)) {
            var yamlFiles = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
            for (var file : yamlFiles) {
                results.addAll(parseFile(file));
            }
        }
        return results;
    }

    /**
     * Parses YAML content from a string. Useful for API input and tests.
     */
    public List<ParsedManifest> parseString(String yamlContent, String sourceName) throws IOException {
        var results = new ArrayList<ParsedManifest>();
        // Split multi-document YAML on --- separator
        String[] documents = yamlContent.split("(?m)^---\\s*$");
        for (String doc : documents) {
            String trimmed = doc.trim();
            if (trimmed.isEmpty()) continue;
            var root = (ObjectNode) yaml.readTree(trimmed);
            var manifest = parseDocument(root, sourceName);
            validator.validate(manifest);
            results.add(manifest);
        }
        return results;
    }

    private ParsedManifest parseDocument(ObjectNode root, String source) {
        String apiVersion = root.path("apiVersion").asText();
        String kind = root.path("kind").asText();

        if (!RiggerManifest.API_VERSION.equals(apiVersion)) {
            throw new ManifestValidationException(List.of(
                "Unsupported apiVersion '" + apiVersion + "' in " + source +
                " (expected " + RiggerManifest.API_VERSION + ")"
            ));
        }

        var metaNode = root.path("metadata");
        ObjectMeta meta;
        try {
            meta = new ObjectMeta(
                metaNode.path("name").asText(null),
                metaNode.path("namespace").asText(null),
                parseStringMap(metaNode.path("labels")),
                parseStringMap(metaNode.path("annotations"))
            );
        } catch (IllegalArgumentException e) {
            throw new ManifestValidationException(List.of(
                "Invalid metadata in " + source + ": " + e.getMessage()));
        }

        Class<?> specClass = kindRegistry.specClassFor(kind)
            .orElseThrow(() -> new ManifestValidationException(
                List.of("Unknown kind '" + kind + "' in " + source)));

        Object spec;
        try {
            spec = yaml.treeToValue(root.path("spec"), specClass);
        } catch (Exception e) {
            throw new ManifestValidationException(
                List.of("Failed to parse spec for " + kind + " '" + meta.qualifiedName() + "': " + e.getMessage()));
        }

        return new ParsedManifest(new RiggerManifest(apiVersion, kind, meta, spec), source);
    }

    private Map<String, String> parseStringMap(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        var map = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return Collections.unmodifiableMap(map);
    }
}