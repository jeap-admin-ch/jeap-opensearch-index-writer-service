package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class MappingDataFieldNamingValidatorTest {

    @TempDir
    File tempDir;

    // -------------------------------------------------------------------------
    // Valid cases
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" is valid snake_case")
    @ValueSource(strings = {
            "id",
            "decree_reference",
            "created_at",
            "v1",
            "field_1",
            "field1",
            "a",
            "x1_y2_z3"
    })
    void validSnakeCaseField_passes(String fieldName) throws IOException {
        ValidationResult result = validate(dataMapping(fieldName, "keyword"));

        assertThat(result.isValid())
                .as("Expected '%s' to be valid snake_case", fieldName)
                .isTrue();
    }

    @Test
    void nestedObjectWithAllSnakeCaseFields_passes() throws IOException {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "data": {
                        "properties": {
                          "decree_reference": {
                            "type": "object",
                            "properties": {
                              "reference_id": { "type": "keyword" },
                              "reference_type": { "type": "keyword" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        assertThat(validate(mapping).isValid()).isTrue();
    }

    @Test
    void missingDataSection_passes() throws IOException {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "search_item": { "properties": {} }
                    }
                  }
                }
                """;
        assertThat(validate(mapping).isValid()).isTrue();
    }

    @Test
    void emptyDataProperties_passes() throws IOException {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "data": { "properties": {} }
                    }
                  }
                }
                """;
        assertThat(validate(mapping).isValid()).isTrue();
    }

    @Test
    void invalidJson_passes() throws IOException {
        // IO errors are handled by IndexTypeMappingSchemaValidator; this validator silently skips them
        File file = new File(tempDir, "bad.json");
        Files.writeString(file.toPath(), "not-valid-json{{{");

        assertThat(MappingDataFieldNamingValidator.validate(file).isValid()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Invalid cases
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\" is not valid snake_case")
    @ValueSource(strings = {
            "decreeReference",        // camelCase
            "DecreeReference",        // PascalCase
            "DECREE_REFERENCE",       // UPPER_SNAKE_CASE
            "_leading_underscore",    // starts with underscore
            "trailing_underscore_",   // ends with underscore
            "double__underscore",     // consecutive underscores
            "1starts_with_digit",     // starts with digit
            "with space",             // contains space
            "with-hyphen"             // contains hyphen
    })
    void invalidFieldName_fails(String fieldName) throws IOException {
        ValidationResult result = validate(dataMapping(fieldName, "keyword"));

        assertThat(result.isValid())
                .as("Expected '%s' to be rejected as non-snake_case", fieldName)
                .isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains(fieldName) && e.contains("snake_case"));
    }

    @Test
    void camelCaseNestedField_failsWithFullPath() throws IOException {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "data": {
                        "properties": {
                          "parent_obj": {
                            "type": "object",
                            "properties": {
                              "childField": { "type": "keyword" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        ValidationResult result = validate(mapping);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("parent_obj.childField"));
    }

    @Test
    void multipleViolations_allReportedInSingleError() throws IOException {
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "data": {
                        "properties": {
                          "decreeId": { "type": "keyword" },
                          "referenceType": { "type": "keyword" },
                          "valid_field": { "type": "keyword" }
                        }
                      }
                    }
                  }
                }
                """;
        ValidationResult result = validate(mapping);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst())
                .contains("decreeId")
                .contains("referenceType")
                .doesNotContain("valid_field");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ValidationResult validate(String mappingJson) throws IOException {
        File file = new File(tempDir, "mapping.json");
        if (file.exists()) {
            file.delete();
        }
        Files.writeString(file.toPath(), mappingJson);
        return MappingDataFieldNamingValidator.validate(file);
    }

    private String dataMapping(String fieldName, String type) {
        return """
                {
                  "mappings": {
                    "properties": {
                      "data": {
                        "properties": {
                          "%s": { "type": "%s" }
                        }
                      }
                    }
                  }
                }
                """.formatted(fieldName, type);
    }
}
