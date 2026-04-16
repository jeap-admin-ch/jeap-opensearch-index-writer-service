package ch.admin.bit.jeap.opensearch.registry.verifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

public class FileNotChangedValidator {

    public static ValidationResult noSystemDeleted(ValidationContext validationContext) {
        File newDescriptorDir = validationContext.getDescriptorDir();
        File oldDescriptorDir = validationContext.getOldDescriptorDir();
        return Arrays.stream(Objects.requireNonNullElse(oldDescriptorDir.list(), new String[0]))
                .map(filename -> fileNotDeleted(newDescriptorDir, filename))
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    public static ValidationResult noIndexTypeDeleted(ValidationContext validationContext) {
        File newSystemDir = systemDir(validationContext, false);
        File oldSystemDir = systemDir(validationContext, true);
        if (!oldSystemDir.exists()) {
            return ValidationResult.ok();
        }
        return Arrays.stream(Objects.requireNonNullElse(oldSystemDir.list(), new String[0]))
                .map(filename -> fileNotDeleted(newSystemDir, filename))
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    public static ValidationResult noExistingMappingsChanged(ValidationContext validationContext) {
        File newIndexTypeDir = indexTypeDir(validationContext, false);
        File oldIndexTypeDir = indexTypeDir(validationContext, true);
        if (!oldIndexTypeDir.exists()) {
            return ValidationResult.ok();
        }
        // Only check mapping files — the descriptor JSON legitimately changes when new versions are added
        String[] mappingFiles = oldIndexTypeDir.list((dir, name) -> name.contains("_mapping_v") && name.endsWith(".json"));
        return Arrays.stream(Objects.requireNonNullElse(mappingFiles, new String[0]))
                .map(filename -> fileNotChanged(oldIndexTypeDir, newIndexTypeDir, filename))
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private static ValidationResult fileNotDeleted(File newFolder, String filename) {
        File newFile = new File(newFolder, filename);
        if (!newFile.exists()) {
            return ValidationResult.fail(
                    "File '%s' exists in master but was deleted. Deleting index types or systems is not allowed."
                            .formatted(newFile.getAbsolutePath()));
        }
        return ValidationResult.ok();
    }

    private static ValidationResult fileNotChanged(File oldFolder, File newFolder, String filename) {
        File newFile = new File(newFolder, filename);
        File oldFile = new File(oldFolder, filename);
        if (!newFile.exists()) {
            return ValidationResult.fail(
                    "File '%s' exists in master but was deleted. Deleting mapping files is not allowed."
                            .formatted(newFile.getAbsolutePath()));
        }
        try {
            if (crc32(newFile) != crc32(oldFile)) {
                return ValidationResult.fail(
                        "File '%s' has changed compared to master. Changing existing mapping files is not allowed."
                                .formatted(newFile.getAbsolutePath()));
            }
        } catch (IOException e) {
            return ValidationResult.fail("Could not compute checksum for '%s': %s"
                    .formatted(newFile.getAbsolutePath(), e.getMessage()));
        }
        return ValidationResult.ok();
    }

    private static long crc32(File file) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(Files.readAllBytes(file.toPath()));
        return crc32.getValue();
    }

    private static File systemDir(ValidationContext ctx, boolean old) {
        return new File(old ? ctx.getOldDescriptorDir() : ctx.getDescriptorDir(), ctx.getSystemName());
    }

    private static File indexTypeDir(ValidationContext ctx, boolean old) {
        return new File(systemDir(ctx, old), ctx.getIndexTypeName().toLowerCase());
    }
}
