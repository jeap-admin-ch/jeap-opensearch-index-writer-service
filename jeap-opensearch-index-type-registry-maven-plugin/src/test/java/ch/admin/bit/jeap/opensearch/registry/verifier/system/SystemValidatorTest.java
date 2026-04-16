package ch.admin.bit.jeap.opensearch.registry.verifier.system;

import ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

import static ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder.mkdirs;
import static ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder.write;
import static org.assertj.core.api.Assertions.assertThat;

class SystemValidatorTest {

    @Test
    void validSystem(@TempDir File root) throws IOException {
        new TestRegistryBuilder(root).buildValidIndexType();
        assertValid(root, "jme");
    }

    @Test
    void systemNameMustBeLowercase(@TempDir File root) throws IOException {
        mkdirs(root, "JME", "jmedecreedocument");
        ValidationResult result = validate(root, "JME");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("lowercase"));
    }

    @Test
    void indexTypeDirMustBeLowercase(@TempDir File root) throws IOException {
        mkdirs(root, "jme", "JmeDecreeDocument");
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("lowercase"));
    }

    @Test
    void indexTypeDirMustNotContainUppercaseName(@TempDir File root) throws IOException {
        // dir name "JmeDecreeDocument" (uppercase start) should fail lowercase check
        File dir = mkdirs(root, "jme", "JmeDecreeDocument");
        write(dir, "JmeDecreeDocument.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("lowercase"));
    }

    @Test
    void descriptorMustMatchDirName(@TempDir File root) throws IOException {
        // dir is "jmedecree" but descriptor is "JmeDecreeDocument.json"
        File indexTypeDir = mkdirs(root, "jme", "jmedecree");
        write(indexTypeDir, "JmeDecreeDocument.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        write(indexTypeDir, "JmeDecreeDocument_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e ->
                e.contains("Directory name") && e.contains("lowercase descriptor name"));
    }

    @Test
    void missingDescriptorFileIsInvalid(@TempDir File root) throws IOException {
        mkdirs(root, "jme", "jmedecreedocument");
        // Directory exists but has no descriptor JSON
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not contain an index type descriptor"));
    }

    @Test
    void illegalExtraFileIsRejected(@TempDir File root) throws IOException {
        File indexTypeDir = mkdirs(root, "jme", "jmedecreedocument");
        write(indexTypeDir, "JmeDecreeDocument.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        write(indexTypeDir, "JmeDecreeDocument_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(indexTypeDir, "README.txt", "This file should not be here");
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("README.txt") && e.contains("not allowed"));
    }

    @Test
    void mappingFilesAreAllowed(@TempDir File root) throws IOException {
        File indexTypeDir = mkdirs(root, "jme", "jmedecreedocument");
        write(indexTypeDir, "JmeDecreeDocument.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        write(indexTypeDir, "JmeDecreeDocument_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        write(indexTypeDir, "JmeDecreeDocument_mapping_v2_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        // This is structurally valid at the SystemValidator level (descriptor content is validated by IndexTypeValidator)
        assertValid(root, "jme");
    }

    @Test
    void multipleIndexTypesInSameSystem(@TempDir File root) throws IOException {
        File dir1 = mkdirs(root, "jme", "jmefoo");
        write(dir1, "JmeFoo.json", descriptorFor("JmeFoo"));
        write(dir1, "JmeFoo_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        File dir2 = mkdirs(root, "jme", "jmebar");
        write(dir2, "JmeBar.json", descriptorFor("JmeBar"));
        write(dir2, "JmeBar_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        assertValid(root, "jme");
    }

    @Test
    void fileInSystemDirRootIsRejected(@TempDir File root) throws IOException {
        File systemDir = mkdirs(root, "jme");
        write(systemDir, "unexpected.json", "{}");
        ValidationResult result = validate(root, "jme");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("is a file") || e.contains("not a directory"));
    }

    private void assertValid(File root, String systemName) {
        ValidationResult result = validate(root, systemName);
        assertThat(result.isValid())
                .as("Expected valid but got errors: %s", result.getErrors())
                .isTrue();
    }

    private ValidationResult validate(File root, String systemName) {
        File systemDir = new File(root, systemName);
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(root)
                .oldDescriptorDir(root)
                .systemName(systemName)
                .systemDir(systemDir)
                .build();
        return SystemValidator.validate(ctx);
    }

    private String descriptorFor(String typeName) {
        return """
                {
                  "system": "JME",
                  "originType": "%s",
                  "description": "Test.",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "%s_mapping_v1_0.json" }
                  ]
                }
                """.formatted(typeName, typeName);
    }
}
