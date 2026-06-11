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
class IndexTypeDescriptorSchemaValidator {
    private static final String SCHEMA_FILE = "resource:/IndexTypeDescriptor.schema.json";
    private static final Schema SCHEMA = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_7)
            .getSchema(SchemaLocation.of(SCHEMA_FILE));
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final File descriptorFile;

    static ValidationResult validate(ValidationContext validationContext) {
        return new IndexTypeDescriptorSchemaValidator(validationContext.getDescriptor()).validateSchema();
    }

    private ValidationResult validateSchema() {
        try {
            JsonNode json = JSON_MAPPER.readTree(descriptorFile);
            List<Error> messages = SCHEMA.validate(json);
            if (!messages.isEmpty()) {
                String errors = messages.stream()
                        .map(Error::getMessage)
                        .collect(Collectors.joining(", "));
                return ValidationResult.fail(
                        "Index type descriptor '%s' does not conform to schema: %s"
                                .formatted(descriptorFile.getAbsolutePath(), errors));
            }
            return ValidationResult.ok();
        } catch (JacksonIOException | StreamReadException e) {
            return ValidationResult.fail(
                    "Cannot read '%s' as JSON: %s".formatted(descriptorFile.getAbsolutePath(), e.getMessage()));
        }
    }
}
