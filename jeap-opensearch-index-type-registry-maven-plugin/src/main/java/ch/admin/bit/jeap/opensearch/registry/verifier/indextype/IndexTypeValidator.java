package ch.admin.bit.jeap.opensearch.registry.verifier.indextype;

import ch.admin.bit.jeap.opensearch.registry.verifier.FileNotChangedValidator;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_DEFINITION;
import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_VERSIONS;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class IndexTypeValidator {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final ValidationContext validationContext;

    public static ValidationResult validate(ValidationContext validationContext) {
        return new IndexTypeValidator(validationContext).validate();
    }

    private ValidationResult validate() {
        ValidationResult schemaResult = IndexTypeDescriptorSchemaValidator.validate(validationContext);
        if (!schemaResult.isValid()) {
            return schemaResult;
        }

        JsonNode descriptorJson;
        try {
            descriptorJson = JSON_MAPPER.readTree(validationContext.getDescriptor());
        } catch (JacksonIOException e) {
            return ValidationResult.fail("Cannot read descriptor '%s': %s"
                    .formatted(validationContext.getDescriptor().getAbsolutePath(), e.getMessage()));
        }

        return ValidationResult.merge(
                validateSystemDirectoryMatchesDeclaredSystem(descriptorJson),
                validateIndexTypeDirectoryMatchesTypeName(),
                validateIndexTypeNameStartsWithSystemName(descriptorJson),
                validateMappingFiles(descriptorJson),
                validateRolesNotChangedWithoutNewVersion(descriptorJson),
                FileNotChangedValidator.noExistingMappingsChanged(validationContext));
    }

    private ValidationResult validateSystemDirectoryMatchesDeclaredSystem(JsonNode descriptorJson) {
        String declaredSystem = descriptorJson.path("system").asString("");
        if (declaredSystem.isBlank()) {
            return ValidationResult.ok();
        }
        String dirName = validationContext.getSystemName();
        if (!declaredSystem.equalsIgnoreCase(dirName)) {
            return ValidationResult.fail(
                    ("System directory '%s' does not match declared system '%s' in descriptor '%s'. " +
                    "The system directory must equal the system name (case-insensitive).")
                            .formatted(dirName, declaredSystem,
                                    validationContext.getDescriptor().getAbsolutePath()));
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateIndexTypeDirectoryMatchesTypeName() {
        String dirName = validationContext.getIndexTypeDir().getName();
        String expected = validationContext.getIndexTypeName().toLowerCase();
        if (!dirName.equals(expected)) {
            return ValidationResult.fail(
                    ("Index type directory '%s' does not match the expected lowercase type name '%s'. " +
                    "The directory must be named exactly '%s'.")
                            .formatted(dirName, expected, expected));
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateIndexTypeNameStartsWithSystemName(JsonNode descriptorJson) {
        String declaredSystem = descriptorJson.path("system").asString("");
        if (declaredSystem.isBlank()) {
            return ValidationResult.ok();
        }
        String titleCaseSystem = declaredSystem.substring(0, 1).toUpperCase()
                + declaredSystem.substring(1).toLowerCase();
        String indexTypeName = validationContext.getIndexTypeName();
        if (!indexTypeName.startsWith(titleCaseSystem)) {
            return ValidationResult.fail(
                    "Index type name '%s' must start with '%s' (system name in title case)."
                            .formatted(indexTypeName, titleCaseSystem));
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateMappingFiles(JsonNode descriptorJson) {
        JsonNode mappingVersions = descriptorJson.path(MAPPING_VERSIONS);
        if (!mappingVersions.isArray() || mappingVersions.isEmpty()) {
            return ValidationResult.fail("Descriptor '%s' must define at least one mapping version"
                    .formatted(validationContext.getDescriptor().getAbsolutePath()));
        }

        List<MappingVersionCompatibilityValidator.MappingVersionRef> versionRefs = new ArrayList<>();
        ValidationResult result = ValidationResult.ok();

        for (JsonNode versionNode : mappingVersions) {
            int major = versionNode.path("major").asInt(-1);
            int minor = versionNode.path("minor").asInt(-1);
            String mappingDefinition = versionNode.path(MAPPING_DEFINITION).asString(null);

            if (major < 0 || minor < 0 || mappingDefinition == null) {
                result = ValidationResult.merge(result, ValidationResult.fail(
                        "Invalid mapping version entry in descriptor '%s'"
                                .formatted(validationContext.getDescriptor().getAbsolutePath())));
                continue;
            }

            File mappingFile = new File(validationContext.getIndexTypeDir(), mappingDefinition);
            result = ValidationResult.merge(result,
                    validateMappingFileExists(mappingFile),
                    validateMappingFileName(mappingFile, major, minor),
                    IndexTypeMappingSchemaValidator.validate(validationContext, mappingFile),
                    MappingDataFieldNamingValidator.validate(mappingFile));

            versionRefs.add(new MappingVersionCompatibilityValidator.MappingVersionRef(major, minor, mappingDefinition));
        }

        if (result.isValid() && versionRefs.size() > 1) {
            result = ValidationResult.merge(result,
                    MappingVersionCompatibilityValidator.validate(
                            validationContext.getIndexTypeDir(),
                            validationContext.getIndexTypeName(),
                            versionRefs));
        }

        return result;
    }

    private ValidationResult validateRolesNotChangedWithoutNewVersion(JsonNode descriptorJson) {
        if (validationContext.getOldDescriptorDir() == null) {
            return ValidationResult.ok();
        }
        File oldIndexTypeDir = new File(
                new File(validationContext.getOldDescriptorDir(), validationContext.getSystemName()),
                validationContext.getIndexTypeName().toLowerCase());
        if (!oldIndexTypeDir.exists()) {
            return ValidationResult.ok();
        }
        File oldDescriptor = new File(oldIndexTypeDir, validationContext.getIndexTypeName() + ".json");
        if (!oldDescriptor.exists()) {
            return ValidationResult.ok();
        }
        JsonNode oldDescriptorJson;
        try {
            oldDescriptorJson = JSON_MAPPER.readTree(oldDescriptor);
        } catch (JacksonIOException e) {
            return ValidationResult.fail("Cannot read old descriptor '%s': %s"
                    .formatted(oldDescriptor.getAbsolutePath(), e.getMessage()));
        }

        Set<String> oldRoles = rolesFrom(oldDescriptorJson);
        Set<String> newRoles = rolesFrom(descriptorJson);
        if (oldRoles.equals(newRoles)) {
            return ValidationResult.ok();
        }

        Set<String> oldVersions = versionsFrom(oldDescriptorJson);
        Set<String> newVersions = versionsFrom(descriptorJson);
        if (newVersions.stream().anyMatch(v -> !oldVersions.contains(v))) {
            return ValidationResult.ok();
        }

        return ValidationResult.fail(
                ("Roles for index type '%s' changed from %s to %s without a new version. " +
                "Roles may only be changed when a new version is introduced.")
                        .formatted(validationContext.getIndexTypeName(), oldRoles, newRoles));
    }

    private static Set<String> rolesFrom(JsonNode descriptor) {
        Set<String> roles = new HashSet<>();
        descriptor.path("roles").forEach(n -> roles.add(n.asString()));
        return roles;
    }

    private static Set<String> versionsFrom(JsonNode descriptor) {
        return StreamSupport.stream(descriptor.path("mappingVersions").spliterator(), false)
                .map(n -> n.path("major").asInt() + "." + n.path("minor").asInt())
                .collect(java.util.stream.Collectors.toSet());
    }

    private ValidationResult validateMappingFileExists(File mappingFile) {
        if (!mappingFile.exists()) {
            return ValidationResult.fail("Mapping file '%s' referenced in descriptor does not exist"
                    .formatted(mappingFile.getAbsolutePath()));
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateMappingFileName(File mappingFile, int major, int minor) {
        String expectedName = validationContext.getIndexTypeName() + "_mapping_v" + major + "_" + minor + ".json";
        if (!mappingFile.getName().equals(expectedName)) {
            return ValidationResult.fail(
                    "Mapping file '%s' does not follow the naming convention. Expected: '%s'"
                            .formatted(mappingFile.getName(), expectedName));
        }
        return ValidationResult.ok();
    }
}
