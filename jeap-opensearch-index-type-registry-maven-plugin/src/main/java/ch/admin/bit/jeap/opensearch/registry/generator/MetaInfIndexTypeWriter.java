package ch.admin.bit.jeap.opensearch.registry.generator;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_DEFINITION;

/**
 * Writes the {@code META-INF/index-types.json} file and copies mapping JSON files
 * to the output resources directory.
 * <p>
 * The {@code index-types.json} file is the runtime index of all available index types
 * and their mapping versions. It is used by the jEAP IndexWriter Service.
 */
public class MetaInfIndexTypeWriter {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final String INDEX_TYPE_SERVICE_FILE = "META-INF/services/ch.admin.bit.jeap.opensearch.indextype.IndexType";

    private final File outputResourcesDir;
    private final Log log;

    public MetaInfIndexTypeWriter(File outputResourcesDir, Log log) {
        this.outputResourcesDir = outputResourcesDir;
        this.log = log;
    }

    /**
     * Writes the META-INF/index-types.json file and copies all mapping files.
     *
     * @param indexTypeEntries list of index type entries to include in the metadata
     */
    public void write(List<IndexTypeEntry> indexTypeEntries) throws MojoExecutionException {
        File metaInfDir = new File(outputResourcesDir, "META-INF");
        try {
            Files.createDirectories(metaInfDir.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create META-INF directory", e);
        }

        ObjectNode root = JSON_MAPPER.createObjectNode();
        ArrayNode indexTypesArray = JSON_MAPPER.createArrayNode();
        root.set("indexTypes", indexTypesArray);

        for (IndexTypeEntry entry : indexTypeEntries) {
            ObjectNode indexTypeNode = JSON_MAPPER.createObjectNode();
            indexTypeNode.put("indexTypeName", entry.indexTypeName());
            indexTypeNode.put("system", entry.system());

            ArrayNode rolesArray = JSON_MAPPER.createArrayNode();
            indexTypeNode.set("roles", rolesArray);
            entry.roles().forEach(rolesArray::add);

            ArrayNode versionsArray = JSON_MAPPER.createArrayNode();
            indexTypeNode.set("mappingVersions", versionsArray);
            for (MappingVersionEntry version : entry.mappingVersions()) {
                ObjectNode versionNode = JSON_MAPPER.createObjectNode();
                versionNode.put("major", version.major());
                versionNode.put("minor", version.minor());
                versionNode.put(MAPPING_DEFINITION, version.mappingDefinition());
                versionNode.put("beanClassName", version.beanClassName());
                versionsArray.add(versionNode);

                // Copy mapping file to META-INF/index-type-mappings/
                copyMappingFile(entry.indexTypeDir(), version.mappingDefinition());
            }

            indexTypesArray.add(indexTypeNode);
        }

        File indexTypesFile = new File(metaInfDir, "index-types.json");
        try {
            JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexTypesFile, root);
        } catch (JacksonIOException e) {
            throw new MojoExecutionException("Cannot write META-INF/index-types.json", e);
        }
        log.info("Written META-INF/index-types.json with %d index types".formatted(indexTypeEntries.size()));

        writeServicesFile(indexTypeEntries, metaInfDir);
    }

    private void writeServicesFile(List<IndexTypeEntry> indexTypeEntries, File metaInfDir)
            throws MojoExecutionException {
        List<String> fqns = indexTypeEntries.stream()
                .flatMap(e -> e.mappingVersions().stream())
                .map(MappingVersionEntry::beanClassName)
                .distinct()
                .toList();
        if (fqns.isEmpty()) return;
        File servicesDir = new File(metaInfDir.getParentFile(),
                INDEX_TYPE_SERVICE_FILE.substring(0, INDEX_TYPE_SERVICE_FILE.lastIndexOf('/')));
        try {
            Files.createDirectories(servicesDir.toPath());
            File servicesFile = new File(metaInfDir.getParentFile(), INDEX_TYPE_SERVICE_FILE);
            Files.writeString(servicesFile.toPath(), String.join("\n", fqns) + "\n");
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write ServiceLoader registration file", e);
        }
        log.info("Registered %d IndexType(s) for ServiceLoader".formatted(fqns.size()));
    }

    private void copyMappingFile(File indexTypeDir, String mappingDefinition) throws MojoExecutionException {
        File mappingFile = new File(indexTypeDir, mappingDefinition);
        File mappingOutputDir = new File(outputResourcesDir, "opensearch");
        try {
            Files.createDirectories(mappingOutputDir.toPath());
            Files.copy(mappingFile.toPath(),
                    new File(mappingOutputDir, mappingDefinition).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot copy mapping file: " + mappingFile, e);
        }
    }

    /**
     * Extracts roles from the descriptor JSON file.
     */
    public static List<String> extractRoles(File descriptorFile) throws MojoExecutionException {
        try {
            JsonNode descriptor = JSON_MAPPER.readTree(descriptorFile);
            JsonNode rolesNode = descriptor.path("roles");
            if (!rolesNode.isArray()) {
                return List.of();
            }
            List<String> roles = new java.util.ArrayList<>();
            rolesNode.iterator().forEachRemaining(entry -> roles.add(entry.asString()));

            return roles;
        } catch (JacksonIOException e) {
            throw new MojoExecutionException("Cannot read descriptor: " + descriptorFile, e);
        }
    }

    /**
     * Extracts the system name from the descriptor JSON file.
     */
    public static String extractSystem(File descriptorFile) {
        JsonNode descriptor = JSON_MAPPER.readTree(descriptorFile);
        return descriptor.path("system").asString("");
    }

    /**
     * Extracts the mapping versions from the descriptor JSON file.
     */
    public static List<Map.Entry<Integer, Integer>> extractMappingVersions(File descriptorFile)
            throws MojoExecutionException {
        try {
            JsonNode descriptor = JSON_MAPPER.readTree(descriptorFile);
            JsonNode versions = descriptor.path("mappingVersions");
            if (!versions.isArray()) {
                return List.of();
            }
            List<Map.Entry<Integer, Integer>> result = new java.util.ArrayList<>();
            for (JsonNode v : versions) {
                result.add(Map.entry(v.path("major").asInt(), v.path("minor").asInt()));
            }
            return result;
        } catch (JacksonIOException e) {
            throw new MojoExecutionException("Cannot read descriptor: " + descriptorFile, e);
        }
    }

    public record IndexTypeEntry(
            String indexTypeName,
            String system,
            List<String> roles,
            List<MappingVersionEntry> mappingVersions,
            File indexTypeDir) {
    }

    public record MappingVersionEntry(
            int major,
            int minor,
            String mappingDefinition,
            String beanClassName) {
    }
}
