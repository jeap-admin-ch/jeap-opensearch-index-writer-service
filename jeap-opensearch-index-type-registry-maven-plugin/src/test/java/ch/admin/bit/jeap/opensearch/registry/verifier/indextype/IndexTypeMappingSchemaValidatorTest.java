package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class IndexTypeMappingSchemaValidatorTest {

    @Test
    void validMappingPassesValidation(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        assertThat(validate(dir, mapping).isValid()).isTrue();
    }

    @Test
    void dynamicTrueFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": true,
                    "properties": {
                      "search_item": { "properties": {
                        "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "major_version": { "type": "integer" },
                        "minor_version": { "type": "integer" }
                      }},
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }},
                      "data": {}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("schema"));
    }

    @Test
    void missingSearchItemFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }},
                      "data": {}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("search_item"));
    }

    @Test
    void missingOriginFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "search_item": { "properties": {
                        "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "major_version": { "type": "integer" },
                        "minor_version": { "type": "integer" }
                      }},
                      "data": {}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("origin"));
    }

    @Test
    void missingDataFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "search_item": { "properties": {
                        "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "major_version": { "type": "integer" },
                        "minor_version": { "type": "integer" }
                      }},
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("data"));
    }

    @Test
    void missingMappingsRootFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                { "notMappings": {} }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("mappings"));
    }

    @Test
    void searchItemMissingMajorVersionFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "search_item": { "properties": {
                        "upserted_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "minor_version": { "type": "integer" }
                      }},
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }},
                      "data": {}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("major_version"));
    }

    @Test
    void searchItemMissingMinorVersionFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "search_item": { "properties": {
                        "upserted_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "major_version": { "type": "integer" }
                      }},
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }},
                      "data": {}
                    }
                  }
                }
                """);
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("minor_version"));
    }

    @Test
    void invalidJsonFails(@TempDir File dir) throws IOException {
        File mapping = write(dir, "mapping.json", "not { valid } json");
        ValidationResult result = validate(dir, mapping);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Cannot read"));
    }

    private ValidationResult validate(File dir, File mappingFile) {
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(dir)
                .oldDescriptorDir(dir)
                .build();
        return IndexTypeMappingSchemaValidator.validate(ctx, mappingFile);
    }

    private File write(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }
}
