package ch.admin.bit.jeap.opensearch.registry.generator;

import ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetaInfIndexTypeWriterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void writesIndexTypesJsonToMetaInf(@TempDir File outputDir, @TempDir File indexTypeDir)
            throws IOException, MojoExecutionException {
        File mappingFile = writeMapping(indexTypeDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        MetaInfIndexTypeWriter writer = new MetaInfIndexTypeWriter(outputDir, new SystemStreamLog());
        writer.write(List.of(entry("JmeDecreeDocument", "JME", List.of("jme_read"),
                List.of(new MetaInfIndexTypeWriter.MappingVersionEntry(1, 0, mappingFile.getName(),
                        "ch.admin.bit.jme.JmeDecreeDocumentV1")),
                indexTypeDir)));

        File indexTypesJson = new File(outputDir, "META-INF/index-types.json");
        assertThat(indexTypesJson).exists();

        JsonNode root = OBJECT_MAPPER.readTree(indexTypesJson);
        assertThat(root.path("indexTypes").isArray()).isTrue();
        assertThat(root.path("indexTypes").size()).isEqualTo(1);
    }

    @Test
    void indexTypesJsonContainsCorrectFields(@TempDir File outputDir, @TempDir File indexTypeDir)
            throws IOException, MojoExecutionException {
        File mappingFile = writeMapping(indexTypeDir, "MyType_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        MetaInfIndexTypeWriter writer = new MetaInfIndexTypeWriter(outputDir, new SystemStreamLog());
        writer.write(List.of(entry("MyType", "SYS", List.of("role_read", "role_admin"),
                List.of(new MetaInfIndexTypeWriter.MappingVersionEntry(1, 0, mappingFile.getName(),
                        "com.example.MyTypeV1")),
                indexTypeDir)));

        JsonNode indexType = OBJECT_MAPPER.readTree(new File(outputDir, "META-INF/index-types.json"))
                .path("indexTypes").get(0);

        assertThat(indexType.path("indexTypeName").asText()).isEqualTo("MyType");
        assertThat(indexType.path("system").asText()).isEqualTo("SYS");
        assertThat(indexType.path("roles").size()).isEqualTo(2);
        assertThat(indexType.path("roles").get(0).asText()).isEqualTo("role_read");
        assertThat(indexType.path("roles").get(1).asText()).isEqualTo("role_admin");
        assertThat(indexType.path("mappingVersions").get(0).path("major").asInt()).isEqualTo(1);
        assertThat(indexType.path("mappingVersions").get(0).path("minor").asInt()).isEqualTo(0);
        assertThat(indexType.path("mappingVersions").get(0).path("beanClassName").asText())
                .isEqualTo("com.example.MyTypeV1");
    }

    @Test
    void copiesMappingFilesToMetaInf(@TempDir File outputDir, @TempDir File indexTypeDir)
            throws IOException, MojoExecutionException {
        File mappingFile = writeMapping(indexTypeDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        MetaInfIndexTypeWriter writer = new MetaInfIndexTypeWriter(outputDir, new SystemStreamLog());
        writer.write(List.of(entry("JmeDecreeDocument", "JME", List.of("jme_read"),
                List.of(new MetaInfIndexTypeWriter.MappingVersionEntry(1, 0, mappingFile.getName(),
                        "ch.admin.bit.jme.JmeDecreeDocumentV1")),
                indexTypeDir)));

        File copiedMapping = new File(outputDir, "opensearch/JmeDecreeDocument_mapping_v1_0.json");
        assertThat(copiedMapping).exists();
        assertThat(Files.readString(copiedMapping.toPath())).contains("mappings");
    }

    @Test
    void multipleIndexTypesAreAllWritten(@TempDir File outputDir, @TempDir File indexTypeDir)
            throws IOException, MojoExecutionException {
        File mapping1 = writeMapping(indexTypeDir, "TypeA_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        File mapping2 = writeMapping(indexTypeDir, "TypeB_mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        MetaInfIndexTypeWriter writer = new MetaInfIndexTypeWriter(outputDir, new SystemStreamLog());
        writer.write(List.of(
                entry("TypeA", "SYS", List.of("sys_read"),
                        List.of(new MetaInfIndexTypeWriter.MappingVersionEntry(1, 0, mapping1.getName(), "SysTypeAV1")),
                        indexTypeDir),
                entry("TypeB", "SYS", List.of("sys_read"),
                        List.of(new MetaInfIndexTypeWriter.MappingVersionEntry(1, 0, mapping2.getName(), "SysTypeBV1")),
                        indexTypeDir)));

        JsonNode root = OBJECT_MAPPER.readTree(new File(outputDir, "META-INF/index-types.json"));
        assertThat(root.path("indexTypes").size()).isEqualTo(2);
    }

    @Test
    void extractRolesFromDescriptor(@TempDir File dir) throws IOException, MojoExecutionException {
        File descriptor = writeDescriptor(dir, "Foo.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        List<String> roles = MetaInfIndexTypeWriter.extractRoles(descriptor);
        assertThat(roles).containsExactly("jme_read");
    }

    @Test
    void extractSystemFromDescriptor(@TempDir File dir) throws IOException, MojoExecutionException {
        File descriptor = writeDescriptor(dir, "Foo.json", TestRegistryBuilder.VALID_DESCRIPTOR);
        String system = MetaInfIndexTypeWriter.extractSystem(descriptor);
        assertThat(system).isEqualTo("JME");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MetaInfIndexTypeWriter.IndexTypeEntry entry(String typeName, String system, List<String> roles,
                                                         List<MetaInfIndexTypeWriter.MappingVersionEntry> versions,
                                                         File indexTypeDir) {
        return new MetaInfIndexTypeWriter.IndexTypeEntry(
                typeName, system, roles, versions, indexTypeDir);
    }

    private File writeMapping(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }

    private File writeDescriptor(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }
}
