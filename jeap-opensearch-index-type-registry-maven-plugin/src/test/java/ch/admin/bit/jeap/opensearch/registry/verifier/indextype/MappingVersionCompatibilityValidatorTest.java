package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder.write;
import static org.assertj.core.api.Assertions.assertThat;

class MappingVersionCompatibilityValidatorTest {

    @Test
    void singleVersionIsAlwaysCompatible(@TempDir File dir) throws IOException {
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(dir, "Foo", List.of(ref(1, 0, "Foo_mapping_v1_0.json")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void minorVersionAddingFieldIsCompatible(@TempDir File dir) throws IOException {
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(dir, "Foo_mapping_v1_1.json", TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(1, 1, "Foo_mapping_v1_1.json")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void minorVersionRemovingFieldIsIncompatible(@TempDir File dir) throws IOException {
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(dir, "Foo_mapping_v1_1.json", TestRegistryBuilder.VALID_MAPPING_V1_1_REMOVES_FIELD);
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(1, 1, "Foo_mapping_v1_1.json")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e ->
                e.contains("not backward compatible") && e.contains("document_id"));
    }

    @Test
    void nestedPropertyRemovalIsIncompatible(@TempDir File dir) throws IOException {
        String withNestedField = mappingWithDataProperties("""
                "decree_reference": {
                  "type": "object",
                  "properties": {
                    "type": { "type": "keyword" },
                    "id":   { "type": "keyword" }
                  }
                },
                "document_id": { "type": "keyword" }
                """);
        String withNestedFieldRemoved = mappingWithDataProperties("""
                "decree_reference": {
                  "type": "object",
                  "properties": {
                    "type": { "type": "keyword" }
                  }
                },
                "document_id": { "type": "keyword" }
                """);
        write(dir, "Foo_mapping_v1_0.json", withNestedField);
        write(dir, "Foo_mapping_v1_1.json", withNestedFieldRemoved);
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(1, 1, "Foo_mapping_v1_1.json")));
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("id") && e.contains("not backward compatible"));
    }

    @Test
    void differentMajorVersionsAreCheckedIndependently(@TempDir File dir) throws IOException {
        // Major 1: v1.0 -> v1.1 is compatible (adds field)
        // Major 2: v2.0 only
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(dir, "Foo_mapping_v1_1.json", TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        write(dir, "Foo_mapping_v2_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(1, 1, "Foo_mapping_v1_1.json"),
                ref(2, 0, "Foo_mapping_v2_0.json")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void majorVersionBreakIsAllowed(@TempDir File dir) throws IOException {
        // v2.0 may remove properties that existed in v1.0 — this is fine (new major)
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(dir, "Foo_mapping_v2_0.json", TestRegistryBuilder.VALID_MAPPING_V1_1_REMOVES_FIELD);
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(2, 0, "Foo_mapping_v2_0.json")));
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void versionsEvaluatedInMinorOrder(@TempDir File dir) throws IOException {
        // v1.2 comes after v1.1, not v1.0 directly — only adjacent minor versions compared
        write(dir, "Foo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(dir, "Foo_mapping_v1_1.json", TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        write(dir, "Foo_mapping_v1_2.json", TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        // Provide versions out of order — validator should sort them
        ValidationResult result = validate(dir, "Foo", List.of(
                ref(1, 2, "Foo_mapping_v1_2.json"),
                ref(1, 0, "Foo_mapping_v1_0.json"),
                ref(1, 1, "Foo_mapping_v1_1.json")));
        assertThat(result.isValid()).isTrue();
    }

    private ValidationResult validate(File dir, String typeName,
                                       List<MappingVersionCompatibilityValidator.MappingVersionRef> versions) {
        return MappingVersionCompatibilityValidator.validate(dir, typeName, versions);
    }

    private MappingVersionCompatibilityValidator.MappingVersionRef ref(int major, int minor, String file) {
        return new MappingVersionCompatibilityValidator.MappingVersionRef(major, minor, file);
    }

    private String mappingWithDataProperties(String dataProps) {
        return """
                {
                  "mappings": {
                    "dynamic": false,
                    "properties": {
                      "search_item": { "properties": {
                        "created":       { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified":      { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "minor_version": { "type": "keyword" }
                      }},
                      "origin": { "properties": {
                        "id": { "type": "keyword" }, "version": { "type": "keyword" },
                        "bp_id": { "type": "keyword" },
                        "created": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                        "reference": { "type": "object", "enabled": false }
                      }},
                      "data": { "type": "object", "properties": { %s } }
                    }
                  }
                }
                """.formatted(dataProps);
    }
}
