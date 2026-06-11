package ch.admin.bit.jeap.opensearch.registry;

import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MojoTest
class IndexTypeRegistryMojoTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @Test
    @Basedir("src/test/resources/valid")
    @InjectMojo(goal = "registry")
    void validRegistry(IndexTypeRegistryMojo mojo) {
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    @Basedir("src/test/resources/dirMissing")
    @InjectMojo(goal = "registry")
    void missingDescriptorDirectory(IndexTypeRegistryMojo mojo) {
        assertThatThrownBy(mojo::execute).hasMessageContaining("does not exist");
    }

    @Test
    @Basedir("src/test/resources/invalidDescriptor")
    @InjectMojo(goal = "registry")
    void invalidDescriptor(IndexTypeRegistryMojo mojo) {
        assertThatThrownBy(mojo::execute).hasMessageContaining("does not conform to schema");
    }

    @Test
    @Basedir("src/test/resources/incompatibleMinorVersion")
    @InjectMojo(goal = "registry")
    void incompatibleMinorVersion(IndexTypeRegistryMojo mojo) {
        assertThatThrownBy(mojo::execute).hasMessageContaining("not backward compatible");
    }

    @Test
    void executeGeneratesSourcesAndMetaInf(@TempDir File tempDir) throws Exception {
        File descriptorDir = TestRegistryBuilder.mkdirs(tempDir, "index-types");
        File outputDir = new File(tempDir, "generated-sources");
        File outputResourcesDir = new File(tempDir, "classes");
        new TestRegistryBuilder(descriptorDir).buildValidIndexType();

        MavenProject project = new MavenProject();
        IndexTypeRegistryMojo mojo = new IndexTypeRegistryMojo();
        setField(mojo, "descriptorDirectory", descriptorDir);
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputResourcesDirectory", outputResourcesDir);
        setField(mojo, "basePackage", "ch.admin.bit.test.index");
        setField(mojo, "project", project);
        setField(mojo, "skipGeneration", false);

        assertDoesNotThrow(mojo::execute);

        File packageDir = new File(outputDir, "ch/admin/bit/test/index/jme/decreedocument");
        assertThat(new File(packageDir, "JmeDecreeDocumentDataV1.java")).exists();
        assertThat(new File(packageDir, "JmeDecreeDocumentIndexTypeV1.java")).exists();
        assertThat(new File(outputResourcesDir, "opensearch/JmeDecreeDocument_mapping_v1_0.json")).exists();

        File indexTypesFile = new File(outputResourcesDir, "META-INF/index-types.json");
        assertThat(indexTypesFile).exists();
        JsonNode root = JSON_MAPPER.readTree(indexTypesFile);
        JsonNode indexTypes = root.path("indexTypes");
        assertThat(indexTypes.isArray()).isTrue();
        assertThat(indexTypes).hasSize(1);
        assertThat(indexTypes.get(0).path("indexTypeName").asString()).isEqualTo("JmeDecreeDocument");

        assertThat(project.getCompileSourceRoots()).contains(outputDir.getAbsolutePath());
    }

    @Test
    void executeSkipsGenerationWhenConfigured(@TempDir File tempDir) throws Exception {
        File descriptorDir = TestRegistryBuilder.mkdirs(tempDir, "index-types");
        File outputDir = new File(tempDir, "generated-sources");
        File outputResourcesDir = new File(tempDir, "classes");
        new TestRegistryBuilder(descriptorDir).buildValidIndexType();

        MavenProject project = new MavenProject();
        IndexTypeRegistryMojo mojo = new IndexTypeRegistryMojo();
        setField(mojo, "descriptorDirectory", descriptorDir);
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputResourcesDirectory", outputResourcesDir);
        setField(mojo, "basePackage", "ch.admin.bit.test.index");
        setField(mojo, "project", project);
        setField(mojo, "skipGeneration", true);

        assertDoesNotThrow(mojo::execute);

        assertThat(Files.exists(outputDir.toPath())).isFalse();
        assertThat(Files.exists(new File(outputResourcesDir, "META-INF/index-types.json").toPath())).isFalse();
        assertThat(project.getCompileSourceRoots()).isEmpty();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
