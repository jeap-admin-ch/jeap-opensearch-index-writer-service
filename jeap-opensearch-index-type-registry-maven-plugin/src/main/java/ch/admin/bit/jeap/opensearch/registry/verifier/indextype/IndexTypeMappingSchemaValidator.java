package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class IndexTypeMappingSchemaValidator {
    private static final String SCHEMA_FILE = "resource:/IndexTypeMappingDescriptor.schema.json";
    private static final JsonSchema SCHEMA = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V7)
            .getSchema(SchemaLocation.of(SCHEMA_FILE));
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final File mappingFile;

    static ValidationResult validate(ValidationContext validationContext, File mappingFile) {
        return new IndexTypeMappingSchemaValidator(mappingFile).validateSchema();
    }

    private ValidationResult validateSchema() {
        try {
            JsonNode json = OBJECT_MAPPER.readTree(mappingFile);
            Set<ValidationMessage> messages = SCHEMA.validate(json);
            if (!messages.isEmpty()) {
                String errors = messages.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining(", "));
                return ValidationResult.fail(
                        "Mapping file '%s' does not conform to schema: %s"
                                .formatted(mappingFile.getAbsolutePath(), errors));
            }
            return ValidationResult.ok();
        } catch (IOException e) {
            return ValidationResult.fail(
                    "Cannot read '%s' as JSON: %s".formatted(mappingFile.getAbsolutePath(), e.getMessage()));
        }
    }
}
