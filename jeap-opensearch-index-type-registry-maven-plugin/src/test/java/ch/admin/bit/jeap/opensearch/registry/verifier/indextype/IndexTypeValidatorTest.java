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

class IndexTypeValidatorTest {

    private static final String INDEX_TYPE_NAME = "JmeDecreeDocument";

    @Test
    void validIndexTypePassesValidation(@TempDir File tempDir) throws IOException {
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        assertThat(validate(tempDir, INDEX_TYPE_NAME).isValid()).isTrue();
    }

    @Test
    void mappingFileNameMajorVersionMismatchFails(@TempDir File tempDir) throws IOException {
        // Descriptor declares major=1 but mappingDefinition filename contains v2_0
        String descriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Major mismatch.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json" }
                  ]
                }
                """;
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, descriptor);
        // Write the file with the name that the descriptor references (v2_0), not what the major/minor dictate (v1_0)
        Files.writeString(new File(typeDir, "JmeDecreeDocument_mapping_v2_0.json").toPath(),
                TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("naming convention")
                && e.contains("JmeDecreeDocument_mapping_v1_0.json"));
    }

    @Test
    void mappingFileNameMinorVersionMismatchFails(@TempDir File tempDir) throws IOException {
        // Descriptor declares minor=0 but mappingDefinition filename contains v1_1
        String descriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Minor mismatch.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" }
                  ]
                }
                """;
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, descriptor);
        Files.writeString(new File(typeDir, "JmeDecreeDocument_mapping_v1_1.json").toPath(),
                TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("naming convention")
                && e.contains("JmeDecreeDocument_mapping_v1_0.json"));
    }

    @Test
    void referencedMappingFileMustExist(@TempDir File tempDir) throws IOException {
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        // No mapping file written
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not exist"));
    }

    @Test
    void mappingFileNameMustFollowConvention(@TempDir File tempDir) throws IOException {
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        Files.writeString(new File(typeDir, "wrong_name_v1_0.json").toPath(),
                TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not exist"));
    }

    @Test
    void multipleVersionsAreValid(@TempDir File tempDir) throws IOException {
        String descriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Two versions.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" }
                  ]
                }
                """;
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, descriptor);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 1, TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        assertThat(validate(tempDir, INDEX_TYPE_NAME).isValid()).isTrue();
    }

    @Test
    void incompatibleMinorVersionFailsValidation(@TempDir File tempDir) throws IOException {
        String descriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Incompatible minor.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" }
                  ]
                }
                """;
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, descriptor);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 1, TestRegistryBuilder.VALID_MAPPING_V1_1_REMOVES_FIELD);
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("not backward compatible"));
    }

    @Test
    void systemDirectoryMismatchFails(@TempDir File root) throws IOException {
        // Descriptor declares system "JME" but directory is named "other"
        File wrongSystemDir = new File(root, "other/jmedecreedocument");
        Files.createDirectories(wrongSystemDir.toPath());
        writeDescriptor(wrongSystemDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(wrongSystemDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(root).oldDescriptorDir(root)
                .systemName("other").systemDir(new File(root, "other"))
                .indexTypeName(INDEX_TYPE_NAME).indexTypeDir(wrongSystemDir)
                .descriptor(new File(wrongSystemDir, INDEX_TYPE_NAME + ".json"))
                .build();
        ValidationResult result = IndexTypeValidator.validate(ctx);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("System directory") && e.contains("other"));
    }

    @Test
    void indexTypeDirectoryNameMismatchFails(@TempDir File root) throws IOException {
        // Directory is named "wrongname" instead of "jmedecreedocument"
        File systemDir = new File(root, "jme");
        File wrongDir = new File(systemDir, "wrongname");
        Files.createDirectories(wrongDir.toPath());
        writeDescriptor(wrongDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(wrongDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(root).oldDescriptorDir(root)
                .systemName("jme").systemDir(systemDir)
                .indexTypeName(INDEX_TYPE_NAME).indexTypeDir(wrongDir)
                .descriptor(new File(wrongDir, INDEX_TYPE_NAME + ".json"))
                .build();
        ValidationResult result = IndexTypeValidator.validate(ctx);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("wrongname") && e.contains("jmedecreedocument"));
    }

    @Test
    void indexTypeNameNotStartingWithSystemFails(@TempDir File root) throws IOException {
        // Type is named "OrderDocument" but system is "JME"
        String wrongTypeName = "OrderDocument";
        String descriptor = """
                {
                  "system": "JME",
                  "originType": "OrderDocument",
                  "description": "Wrong prefix.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "OrderDocument_mapping_v1_0.json" }
                  ]
                }
                """;
        File typeDir = new File(root, "jme/orderdocument");
        Files.createDirectories(typeDir.toPath());
        writeDescriptor(typeDir, wrongTypeName, descriptor);
        writeMapping(typeDir, wrongTypeName, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(root).oldDescriptorDir(root)
                .systemName("jme").systemDir(new File(root, "jme"))
                .indexTypeName(wrongTypeName).indexTypeDir(typeDir)
                .descriptor(new File(typeDir, wrongTypeName + ".json"))
                .build();
        ValidationResult result = IndexTypeValidator.validate(ctx);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("OrderDocument") && e.contains("Jme"));
    }

    @Test
    void invalidDescriptorSchemaFailsBeforeMappingCheck(@TempDir File tempDir) throws IOException {
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, "{ \"system\": \"JME\" }"); // missing required fields
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("schema"));
    }

    @Test
    void invalidMappingSchemaFailsValidation(@TempDir File tempDir) throws IOException {
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, "{ \"mappings\": { \"dynamic\": true, \"properties\": { \"search_item\": {\"properties\":{}}, \"origin\": {\"properties\":{}}, \"data\": {} } } }");
        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void rolesUnchangedPassesValidation(@TempDir File newDir, @TempDir File oldDir) throws IOException {
        writeOldDescriptor(newDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeOldDescriptor(oldDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeOldMapping(oldDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        assertThat(validateWithMaster(newDir, oldDir, INDEX_TYPE_NAME).isValid()).isTrue();
    }

    @Test
    void rolesChangedWithNewMajorVersionPassesValidation(@TempDir File newDir, @TempDir File oldDir) throws IOException {
        String newDescriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "New roles with new major.",
                  "roles": ["jme_admin"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json" }
                  ]
                }
                """;
        writeOldDescriptor(newDir, INDEX_TYPE_NAME, newDescriptor);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 2, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeOldDescriptor(oldDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeOldMapping(oldDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        assertThat(validateWithMaster(newDir, oldDir, INDEX_TYPE_NAME).isValid()).isTrue();
    }

    @Test
    void rolesChangedWithoutNewMajorVersionFailsValidation(@TempDir File newDir, @TempDir File oldDir) throws IOException {
        String newDescriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Changed roles, no new major.",
                  "roles": ["jme_admin"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" }
                  ]
                }
                """;
        writeOldDescriptor(newDir, INDEX_TYPE_NAME, newDescriptor);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeOldDescriptor(oldDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeOldMapping(oldDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        ValidationResult result = validateWithMaster(newDir, oldDir, INDEX_TYPE_NAME);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Roles") && e.contains("new version"));
    }

    @Test
    void rolesChangedWithNewMinorVersionPassesValidation(@TempDir File newDir, @TempDir File oldDir) throws IOException {
        String newDescriptor = """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Changed roles with new minor.",
                  "roles": ["jme_admin"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" }
                  ]
                }
                """;
        writeOldDescriptor(newDir, INDEX_TYPE_NAME, newDescriptor);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);
        writeOldMapping(newDir, INDEX_TYPE_NAME, 1, 1, TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);
        writeOldDescriptor(oldDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeOldMapping(oldDir, INDEX_TYPE_NAME, 1, 0, TestRegistryBuilder.VALID_MAPPING_V1_0);

        assertThat(validateWithMaster(newDir, oldDir, INDEX_TYPE_NAME).isValid()).isTrue();
    }

    /** typeDir returns {base}/jme/{typeName.toLowerCase()} and creates it. */
    private File typeDir(File base) throws IOException {
        File dir = new File(base, "jme/" + INDEX_TYPE_NAME.toLowerCase());
        Files.createDirectories(dir.toPath());
        return dir;
    }

    private ValidationResult validate(File baseDir, String typeName) {
        File indexTypeDir = new File(baseDir, "jme/" + typeName.toLowerCase());
        File descriptor = new File(indexTypeDir, typeName + ".json");
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(baseDir)
                .oldDescriptorDir(baseDir)
                .systemName("jme")
                .systemDir(new File(baseDir, "jme"))
                .indexTypeName(typeName)
                .indexTypeDir(indexTypeDir)
                .descriptor(descriptor)
                .build();
        return IndexTypeValidator.validate(ctx);
    }

    private ValidationResult validateWithMaster(File newDir, File oldDir, String typeName) throws IOException {
        File indexTypeDir = new File(newDir, "jme/" + typeName.toLowerCase());
        Files.createDirectories(indexTypeDir.toPath());
        File descriptor = new File(indexTypeDir, typeName + ".json");
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(newDir)
                .oldDescriptorDir(oldDir)
                .systemName("jme")
                .systemDir(new File(newDir, "jme"))
                .indexTypeName(typeName)
                .indexTypeDir(indexTypeDir)
                .descriptor(descriptor)
                .build();
        return IndexTypeValidator.validate(ctx);
    }

    @Test
    void camelCaseDataFieldNameFailsValidation(@TempDir File tempDir) throws IOException {
        String mappingWithCamelCase = TestRegistryBuilder.VALID_MAPPING_V1_0.replace(
                "\"document_id\"", "\"documentId\"");
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, mappingWithCamelCase);

        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("documentId") && e.contains("snake_case"));
    }

    @Test
    void camelCaseNestedDataFieldNameFailsValidation(@TempDir File tempDir) throws IOException {
        String mappingWithCamelCase = TestRegistryBuilder.VALID_MAPPING_V1_0.replace(
                "\"decree_reference\"", "\"decreeReference\"");
        File typeDir = typeDir(tempDir);
        writeDescriptor(typeDir, INDEX_TYPE_NAME, TestRegistryBuilder.VALID_DESCRIPTOR);
        writeMapping(typeDir, INDEX_TYPE_NAME, 1, 0, mappingWithCamelCase);

        ValidationResult result = validate(tempDir, INDEX_TYPE_NAME);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("decreeReference") && e.contains("snake_case"));
    }

    private void writeDescriptor(File dir, String typeName, String content) throws IOException {
        Files.writeString(new File(dir, typeName + ".json").toPath(), content);
    }

    private void writeMapping(File dir, String typeName, int major, int minor, String content) throws IOException {
        Files.writeString(new File(dir, "%s_mapping_v%d_%d.json".formatted(typeName, major, minor)).toPath(), content);
    }

    private void writeOldDescriptor(File oldDir, String typeName, String content) throws IOException {
        File indexTypeDir = new File(oldDir, "jme/" + typeName.toLowerCase());
        Files.createDirectories(indexTypeDir.toPath());
        Files.writeString(new File(indexTypeDir, typeName + ".json").toPath(), content);
    }

    private void writeOldMapping(File oldDir, String typeName, int major, int minor, String content) throws IOException {
        File indexTypeDir = new File(oldDir, "jme/" + typeName.toLowerCase());
        Files.createDirectories(indexTypeDir.toPath());
        Files.writeString(new File(indexTypeDir, "%s_mapping_v%d_%d.json".formatted(typeName, major, minor)).toPath(), content);
    }
}
