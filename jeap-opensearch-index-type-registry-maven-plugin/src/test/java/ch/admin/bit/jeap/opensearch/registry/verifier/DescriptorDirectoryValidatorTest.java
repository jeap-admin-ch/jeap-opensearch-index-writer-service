package ch.admin.bit.jeap.opensearch.registry.verifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptorDirectoryValidatorTest {

    @Test
    void directoryDoesNotExist() {
        File nonExistent = new File("/non/existent/path");
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(nonExistent)
                .oldDescriptorDir(nonExistent)
                .build();
        ValidationResult result = DescriptorDirectoryValidator.validate(ctx);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not exist"));
    }

    @Test
    void emptyDirectoryIsValid(@TempDir File tmpDir) {
        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(tmpDir)
                .oldDescriptorDir(tmpDir)
                .build();
        ValidationResult result = DescriptorDirectoryValidator.validate(ctx);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void systemNameMustBeLowercase(@TempDir File tmpDir) throws IOException {
        File upperCaseSystem = new File(tmpDir, "JME");
        Files.createDirectories(upperCaseSystem.toPath());

        ValidationContext ctx = ValidationContext.builder()
                .descriptorDir(tmpDir)
                .oldDescriptorDir(tmpDir)
                .build();
        ValidationResult result = DescriptorDirectoryValidator.validate(ctx);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("lowercase"));
    }
}
