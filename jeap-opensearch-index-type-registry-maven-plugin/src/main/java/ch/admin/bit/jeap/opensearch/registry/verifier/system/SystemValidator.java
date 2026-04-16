package ch.admin.bit.jeap.opensearch.registry.verifier.system;

import ch.admin.bit.jeap.opensearch.registry.verifier.FileNotChangedValidator;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import ch.admin.bit.jeap.opensearch.registry.verifier.indextype.IndexTypeValidator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class SystemValidator {
    private final File systemDirectory;
    private final String systemName;
    private final ValidationContext validationContext;
    private final List<String> indexTypeNames = new LinkedList<>();

    public static ValidationResult validate(ValidationContext validationContext) {
        SystemValidator validator = new SystemValidator(
                validationContext.getSystemDir(),
                validationContext.getSystemName(),
                validationContext);

        ValidationResult result = ValidationResult.merge(
                validator.checkDirectory(),
                validator.checkSystemName(),
                validator.checkSubDirs(),
                FileNotChangedValidator.noIndexTypeDeleted(validationContext));

        if (!result.isValid()) {
            return result;
        }
        return validator.checkIndexTypeDescriptors();
    }

    private ValidationResult checkDirectory() {
        String path = systemDirectory.getAbsolutePath();
        if (!systemDirectory.isDirectory()) {
            return ValidationResult.fail("'%s' is not a directory, but a system directory is expected".formatted(path));
        }
        if (!systemDirectory.canRead()) {
            return ValidationResult.fail("Directory '%s' is not readable".formatted(path));
        }
        return ValidationResult.ok();
    }

    private ValidationResult checkSystemName() {
        if (!systemName.equals(systemName.toLowerCase())) {
            return ValidationResult.fail(
                    "System directory name '%s' must be all lowercase".formatted(systemName));
        }
        return ValidationResult.ok();
    }

    private ValidationResult checkSubDirs() {
        indexTypeNames.clear();
        String[] subDirs = systemDirectory.list();
        if (subDirs == null) {
            return ValidationResult.ok();
        }
        return Arrays.stream(subDirs)
                .map(name -> new File(systemDirectory, name))
                .map(this::checkIndexTypeSubDir)
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private ValidationResult checkIndexTypeSubDir(File dir) {
        ValidationResult result = checkSubDirIsReadable(dir);
        if (!result.isValid()) {
            return result;
        }
        return checkIndexTypeSubDirContent(dir);
    }

    private ValidationResult checkSubDirIsReadable(File dir) {
        String path = dir.getAbsolutePath();
        if (!dir.isDirectory()) {
            return ValidationResult.fail("'%s' is expected to be an index type directory but is a file".formatted(path));
        }
        if (!dir.canRead()) {
            return ValidationResult.fail("Directory '%s' is not readable".formatted(path));
        }
        if (!dir.getName().equals(dir.getName().toLowerCase())) {
            return ValidationResult.fail("Index type directory '%s' must be all lowercase".formatted(path));
        }
        return ValidationResult.ok();
    }

    private ValidationResult checkIndexTypeSubDirContent(File subDir) {
        Optional<String> descriptorNameOpt = findDescriptorName(subDir);
        if (descriptorNameOpt.isEmpty()) {
            return ValidationResult.fail(
                    "Directory '%s' does not contain an index type descriptor JSON file with a PascalCase name matching the directory name"
                            .formatted(subDir.getAbsolutePath()));
        }
        String descriptorName = descriptorNameOpt.get();
        indexTypeNames.add(descriptorName);

        // Validate that the descriptor name matches the directory: dirName == descriptorName.toLowerCase()
        if (!subDir.getName().equals(descriptorName.toLowerCase())) {
            return ValidationResult.fail(
                    "Directory name '%s' must equal the lowercase descriptor name '%s'"
                            .formatted(subDir.getName(), descriptorName.toLowerCase()));
        }

        // Check for illegal files (only descriptor JSON and mapping JSON files allowed)
        return checkOnlyAllowedFilesPresent(subDir, descriptorName);
    }

    private Optional<String> findDescriptorName(File subDir) {
        FilenameFilter jsonFilter = (dir, name) -> name.endsWith(".json");
        String[] jsonFiles = subDir.list(jsonFilter);
        if (jsonFiles == null) {
            return Optional.empty();
        }
        // The descriptor file is <IndexTypeName>.json (no underscore-version suffix)
        return Arrays.stream(jsonFiles)
                .filter(f -> !f.contains("_mapping_v"))
                .map(f -> f.substring(0, f.length() - 5)) // remove .json
                .filter(name -> name.equals(name.substring(0, 1).toUpperCase() + name.substring(1))) // starts uppercase
                .findFirst();
    }

    private ValidationResult checkOnlyAllowedFilesPresent(File subDir, String descriptorName) {
        String[] files = Objects.requireNonNull(subDir.list());
        return Arrays.stream(files)
                .filter(f -> !isAllowedFile(f, descriptorName))
                .map(f -> ValidationResult.fail(
                        "File '%s' is not allowed in index type directory '%s'. Only the descriptor JSON and mapping JSON files are permitted."
                                .formatted(f, subDir.getAbsolutePath())))
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private boolean isAllowedFile(String filename, String descriptorName) {
        if (filename.equals(descriptorName + ".json")) {
            return true;
        }
        // Allow mapping files: <DescriptorName>_mapping_v<major>_<minor>.json
        return filename.matches(descriptorName + "_mapping_v\\d+_\\d+\\.json");
    }

    private ValidationResult checkIndexTypeDescriptors() {
        return indexTypeNames.stream()
                .map(this::checkIndexTypeDescriptor)
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private ValidationResult checkIndexTypeDescriptor(String descriptorName) {
        File indexTypeDir = new File(systemDirectory, descriptorName.toLowerCase());
        ValidationContext subContext = validationContext.toBuilder()
                .indexTypeName(descriptorName)
                .indexTypeDir(indexTypeDir)
                .descriptor(new File(indexTypeDir, descriptorName + ".json"))
                .build();
        return IndexTypeValidator.validate(subContext);
    }
}
