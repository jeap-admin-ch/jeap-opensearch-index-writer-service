package ch.admin.bit.jeap.opensearch.registry.verifier;

import ch.admin.bit.jeap.opensearch.registry.verifier.system.SystemValidator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class DescriptorDirectoryValidator {
    private final File descriptorDirectory;
    private final ValidationContext validationContext;

    public static ValidationResult validate(ValidationContext validationContext) {
        DescriptorDirectoryValidator validator = new DescriptorDirectoryValidator(
                validationContext.getDescriptorDir(), validationContext);

        ValidationResult dirResult = validator.checkDirectory();
        if (!dirResult.isValid()) {
            return dirResult;
        }
        return ValidationResult.merge(
                FileNotChangedValidator.noSystemDeleted(validationContext),
                validator.checkSystems());
    }

    private ValidationResult checkDirectory() {
        String absolutePath = descriptorDirectory.getAbsolutePath();
        if (!descriptorDirectory.exists()) {
            return ValidationResult.fail("Directory '%s' does not exist".formatted(absolutePath));
        }
        if (!descriptorDirectory.isDirectory()) {
            return ValidationResult.fail("'%s' is not a directory".formatted(absolutePath));
        }
        if (!descriptorDirectory.canRead()) {
            return ValidationResult.fail("Directory '%s' is not readable".formatted(absolutePath));
        }
        return ValidationResult.ok();
    }

    private ValidationResult checkSystems() {
        String[] systemNames = Objects.requireNonNull(descriptorDirectory.list());
        return Arrays.stream(systemNames)
                .map(this::checkSystem)
                .reduce(ValidationResult.ok(), ValidationResult::merge);
    }

    private ValidationResult checkSystem(String systemName) {
        ValidationContext subContext = validationContext.toBuilder()
                .systemName(systemName)
                .systemDir(new File(descriptorDirectory, systemName))
                .build();
        return SystemValidator.validate(subContext);
    }
}
