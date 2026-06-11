package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import com.networknt.schema.*;
import com.networknt.schema.Error;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class IndexTypeMappingSchemaValidator {
    private static final String SCHEMA_FILE = "resource:/IndexTypeMappingDescriptor.schema.json";
    private static final Schema SCHEMA = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(SchemaLocation.of(SCHEMA_FILE));
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final File mappingFile;

    static ValidationResult validate(ValidationContext validationContext, File mappingFile) {
        return new IndexTypeMappingSchemaValidator(mappingFile).validateSchema();
    }

    private ValidationResult validateSchema() {
        try {
            JsonNode json = JSON_MAPPER.readTree(mappingFile);
        List<Error> errors = SCHEMA.validate(json);
        if (!errors.isEmpty()) {
                String errorMessages = errors.stream()
                        .map(Error::getMessage)
                        .collect(Collectors.joining(", "));
                return ValidationResult.fail(
                        "Mapping file '%s' does not conform to schema: %s"
                                .formatted(mappingFile.getAbsolutePath(), errorMessages));
            }
            return ValidationResult.ok();
        } catch (JacksonIOException | StreamReadException e) {
            return ValidationResult.fail(
                    "Cannot read '%s' as JSON: %s".formatted(mappingFile.getAbsolutePath(), e.getMessage()));
        }
    }
}
