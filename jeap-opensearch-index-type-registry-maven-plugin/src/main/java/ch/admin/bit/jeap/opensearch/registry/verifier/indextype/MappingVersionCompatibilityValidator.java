package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates minor version compatibility: within the same major version,
 * newer minor versions must only ADD new optional properties to the data section
 * (backward compatible changes). Existing properties must not be removed or modified.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class MappingVersionCompatibilityValidator {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final File indexTypeDir;
    private final String indexTypeName;

    static ValidationResult validate(File indexTypeDir, String indexTypeName,
                                     List<MappingVersionRef> versions) {
        return new MappingVersionCompatibilityValidator(indexTypeDir, indexTypeName)
                .validateCompatibility(versions);
    }

    private ValidationResult validateCompatibility(List<MappingVersionRef> versions) {
        // Group by major version
        Map<Integer, List<MappingVersionRef>> byMajor = new java.util.TreeMap<>();
        for (MappingVersionRef v : versions) {
            byMajor.computeIfAbsent(v.major(), k -> new ArrayList<>()).add(v);
        }

        // For each major version, check minor version compatibility
        return byMajor.values().stream()
                .map(this::validateMajorGroup)
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private ValidationResult validateMajorGroup(List<MappingVersionRef> versionGroup) {
        // Sort by minor version
        List<MappingVersionRef> sorted = versionGroup.stream()
                .sorted(java.util.Comparator.comparingInt(MappingVersionRef::minor))
                .toList();

        ValidationResult result = ValidationResult.ok();
        for (int i = 1; i < sorted.size(); i++) {
            MappingVersionRef previous = sorted.get(i - 1);
            MappingVersionRef current = sorted.get(i);
            result = ValidationResult.merge(result, checkMinorCompatibility(previous, current));
        }
        return result;
    }

    private ValidationResult checkMinorCompatibility(MappingVersionRef previous, MappingVersionRef current) {
        File prevFile = new File(indexTypeDir, previous.mappingDefinition());
        File currFile = new File(indexTypeDir, current.mappingDefinition());

        JsonNode prevData;
        JsonNode currData;
        try {
            prevData = getDataProperties(JSON_MAPPER.readTree(prevFile));
            currData = getDataProperties(JSON_MAPPER.readTree(currFile));
        } catch (JacksonIOException e) {
            return ValidationResult.fail("Cannot read mapping file for compatibility check: " + e.getMessage());
        }

        if (prevData == null || currData == null) {
            return ValidationResult.ok();
        }

        // All properties in the previous version must still exist in the new version (no removals)
        return validateNoPropertiesRemoved(prevData, currData, previous, current);
    }

    private ValidationResult validateNoPropertiesRemoved(JsonNode prevData, JsonNode currData,
                                                          MappingVersionRef previous, MappingVersionRef current) {
        List<String> removedProperties = new ArrayList<>();
        collectRemovedProperties(prevData, currData, "", removedProperties);

        if (!removedProperties.isEmpty()) {
            String message = ("Minor version %s is not backward compatible with v%d.%d for index type '%s': "
                    + "the following data properties were removed: %s. "
                    + "Minor versions must only add new properties.")
                    .formatted(current.versionLabel(), previous.major(), previous.minor(),
                            indexTypeName, String.join(", ", removedProperties));
            return ValidationResult.fail(message);
        }
        return ValidationResult.ok();
    }

    private void collectRemovedProperties(JsonNode prev, JsonNode curr, String path, List<String> removed) {
        if (!prev.isObject() || !curr.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : prev.properties()) {
            String fieldName = entry.getKey();
            String fieldPath = path.isEmpty() ? fieldName : path + "." + fieldName;

            if (!curr.has(fieldName)) {
                removed.add(fieldPath);
            } else if (entry.getValue().isObject() && entry.getValue().has("properties")) {
                // Recurse into nested objects
                JsonNode prevNested = entry.getValue().get("properties");
                JsonNode currNested = curr.get(fieldName).path("properties");
                if (!currNested.isMissingNode()) {
                    collectRemovedProperties(prevNested, currNested, fieldPath, removed);
                }
            }
        }
    }

    private JsonNode getDataProperties(JsonNode mapping) {
        JsonNode dataNode = mapping.path("mappings").path("properties").path("data").path("properties");
        return dataNode.isMissingNode() ? null : dataNode;
    }

    record MappingVersionRef(int major, int minor, String mappingDefinition) {
        String versionLabel() {
            return "v%d.%d".formatted(major, minor);
        }
    }
}
