package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_VERSIONS;
import static org.assertj.core.api.Assertions.assertThat;

class IndexTypeDescriptorSchemaValidatorTest {

    @Test
    void validDescriptorPassesValidation(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "JmeDecreeDocument.json", """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Test index type.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" }
                  ]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void allOptionalFieldsPresent(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "JmeDecreeDocument.json", """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Test index type.",
                  "documentationUrl": "https://example.com/docs",
                  "roles": ["jme_read", "jme_admin"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json" }
                  ]
                }
                """);
        assertThat(validate(descriptor).isValid()).isTrue();
    }

    @Test
    void missingOriginTypeFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "description": "Missing originType.",
                  "roles": ["jme_read"],
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("originType"));
    }

    @Test
    void missingSystemFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "originType": "Bad",
                  "description": "Missing system.",
                  "roles": ["jme_read"],
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("system"));
    }

    @Test
    void missingDescriptionFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "roles": ["jme_read"],
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("description"));
    }

    @Test
    void missingRolesFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "description": "No roles.",
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("roles"));
    }

    @Test
    void emptyRolesArrayFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "description": "Empty roles.",
                  "roles": [],
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void missingMappingVersionsFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "description": "No mapping versions.",
                  "roles": ["jme_read"]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains(MAPPING_VERSIONS));
    }

    @Test
    void mappingVersionMissingMajorFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "description": "Bad version.",
                  "roles": ["jme_read"],
                  "mappingVersions": [{ "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }]
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void unknownAdditionalPropertyFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", """
                {
                  "system": "JME",
                  "originType": "Bad",
                  "description": "Has extra field.",
                  "roles": ["jme_read"],
                  "mappingVersions": [{ "major": 1, "minor": 0, "mappingDefinition": "Bad_mapping_v1_0.json" }],
                  "unknownField": "not allowed"
                }
                """);
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void invalidJsonFails(@TempDir File dir) throws IOException {
        File descriptor = write(dir, "Bad.json", "this is not json {{{");
        ValidationResult result = validate(descriptor);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Cannot read"));
    }

    private ValidationResult validate(File descriptor) {
        ValidationContext ctx = ValidationContext.builder()
                .descriptor(descriptor)
                .descriptorDir(descriptor.getParentFile())
                .oldDescriptorDir(descriptor.getParentFile())
                .build();
        return IndexTypeDescriptorSchemaValidator.validate(ctx);
    }

    private File write(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }
}
