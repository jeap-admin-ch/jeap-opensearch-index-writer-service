package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that all field names inside {@code mappings.properties.data.properties} (recursively)
 * follow the snake_case convention required for OpenSearch field names.
 * <p>
 * The service writes documents using Jackson with {@code PropertyNamingStrategies.SNAKE_CASE}, so
 * any camelCase field names in the mapping would never match the actual data written to OpenSearch.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class MappingDataFieldNamingValidator {

    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final File mappingFile;

    static ValidationResult validate(File mappingFile) {
        return new MappingDataFieldNamingValidator(mappingFile).validateFieldNames();
    }

    private ValidationResult validateFieldNames() {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(mappingFile);
        } catch (IOException e) {
            // IO errors are already caught by IndexTypeMappingSchemaValidator — skip here
            return ValidationResult.ok();
        }

        JsonNode dataProperties = root.path("mappings").path("properties").path("data").path("properties");
        if (dataProperties.isMissingNode() || !dataProperties.isObject()) {
            return ValidationResult.ok();
        }

        List<String> violations = new ArrayList<>();
        collectViolations(dataProperties, "", violations);

        if (violations.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(
                "Mapping file '%s' contains data field names that are not snake_case: %s. All field names must match [a-z][a-z0-9]*(_[a-z0-9]+)*"
                        .formatted(mappingFile.getName(), violations));
    }

    private void collectViolations(JsonNode propertiesNode, String path, List<String> violations) {
        propertiesNode.properties().forEach(entry -> {
            String name = entry.getKey();
            String fullPath = path.isEmpty() ? name : path + "." + name;
            if (!SNAKE_CASE.matcher(name).matches()) {
                violations.add(fullPath);
            }
            JsonNode nested = entry.getValue().path("properties");
            if (!nested.isMissingNode() && nested.isObject()) {
                collectViolations(nested, fullPath, violations);
            }
        });
    }
}
