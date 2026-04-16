package ch.admin.bit.jeap.opensearch.registry.generator;

import ch.admin.bit.jeap.opensearch.registry.TestRegistryBuilder;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaBeanGeneratorTest {

    private static final String BASE_PACKAGE = "ch.admin.bit.test.index";

    @Test
    void generateReturnsIndexTypeFqn(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        JavaBeanGenerator generator = new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog());
        String fqn = generate(generator, "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        assertThat(fqn).isEqualTo(BASE_PACKAGE + ".jme.decreedocument.JmeDecreeDocumentIndexTypeV1");
    }

    @Test
    void generatesDataRecord(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        assertThat(source).contains("package " + BASE_PACKAGE + ".jme.decreedocument;");
        assertThat(source).contains("public record JmeDecreeDocumentDataV1(");
    }

    @Test
    void generatesIndexTypeSingleton(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentIndexTypeV1.java");
        assertThat(source).contains("public final class JmeDecreeDocumentIndexTypeV1 implements IndexType<JmeDecreeDocumentDataV1>");
        assertThat(source).contains("public static final JmeDecreeDocumentIndexTypeV1 INSTANCE");
        assertThat(source).contains("return \"JME\";");
        assertThat(source).contains("return \"JmeDecreeDocument\";");
        assertThat(source).contains("return 1;"); // majorVersion
        assertThat(source).contains("return 0;"); // minorVersion
    }

    @Test
    void indexTypeSingletonContainsDescriptorMetadata(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);

        new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog())
                .generate("JmeDecreeDocument", "JME", "Some description", "https://example.com",
                        List.of("jme_read"), 1, 0, v10, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentIndexTypeV1.java");
        assertThat(source).contains("Some description");
        assertThat(source).contains("https://example.com");
        assertThat(source).contains("\"jme_read\"");
    }

    @Test
    void mappingDefinitionReferencesOpensearchResource(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog())
                .generate("JmeDecreeDocument", "JME", "", "", List.of(), 1, 0, v10, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentIndexTypeV1.java");
        assertThat(source).contains("/opensearch/JmeDecreeDocument_mapping_v1_0.json");
    }

    @Test
    void dataRecordHasJsonPropertyForSnakeCaseFields(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        assertThat(source).contains("@JsonProperty(\"document_id\")");
        assertThat(source).contains("documentId");
        assertThat(source).contains("@JsonProperty(\"created_at\")");
        assertThat(source).contains("createdAt");
    }

    @Test
    void dataRecordHasNestedRecordForObjectFields(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        assertThat(source).contains("public record DecreeReference(");
        assertThat(source).contains("DecreeReference decreeReference");
    }

    @Test
    void dataRecordUsesInstantForDateFields(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        assertThat(source).contains("import java.time.Instant;");
        assertThat(source).contains("Instant createdAt");
    }

    @Test
    void dataRecordDoesNotMapSearchItemOrOriginFields(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        assertThat(source).doesNotContain("bpId");
        assertThat(source).doesNotContain("minorVersion");
    }

    @Test
    void majorVersionIsIncludedInClassName(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        JavaBeanGenerator generator = new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog());
        String fqn1 = generate(generator, "MyType", "SYS", 1, 0, List.of(v10));
        String fqn2 = generate(generator, "MyType", "SYS", 2, 0, List.of(v10));

        assertThat(fqn1).endsWith("MyTypeIndexTypeV1");
        assertThat(fqn2).endsWith("MyTypeIndexTypeV2");
        assertThat(sourceFile(outputDir, "SYS", "MyType", "MyTypeDataV1.java")).exists();
        assertThat(sourceFile(outputDir, "SYS", "MyType", "MyTypeDataV2.java")).exists();
        assertThat(sourceFile(outputDir, "SYS", "MyType", "MyTypeIndexTypeV1.java")).exists();
        assertThat(sourceFile(outputDir, "SYS", "MyType", "MyTypeIndexTypeV2.java")).exists();
    }

    @Test
    void generatesCompleteDataRecordFile(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String expected = """
                package ch.admin.bit.test.index.jme.decreedocument;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import java.time.Instant;

                public record JmeDecreeDocumentDataV1(
                    @JsonProperty("document_id") String documentId,
                    @JsonProperty("decree_reference") DecreeReference decreeReference,
                    @JsonProperty("created_at") Instant createdAt
                ) {

                    public record DecreeReference(
                        String type,
                        String id
                    ) {}
                }
                """;
        assertThat(readSource(outputDir, "JmeDecreeDocumentDataV1.java")).isEqualTo(expected);
    }

    @Test
    void generatesCompleteIndexTypeFile(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "JmeDecreeDocument_mapping_v1_0.json",
                TestRegistryBuilder.VALID_MAPPING_V1_0);

        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 0, List.of(v10));

        String expected = """
                package ch.admin.bit.test.index.jme.decreedocument;

                import ch.admin.bit.jeap.opensearch.indextype.IndexType;
                import java.io.InputStream;
                import java.util.List;
                import java.util.function.Supplier;

                public final class JmeDecreeDocumentIndexTypeV1 implements IndexType<JmeDecreeDocumentDataV1> {

                    public static final JmeDecreeDocumentIndexTypeV1 INSTANCE = new JmeDecreeDocumentIndexTypeV1();
                    public JmeDecreeDocumentIndexTypeV1() {}

                    @Override public String system()           { return "JME"; }
                    @Override public String originType()       { return "JmeDecreeDocument"; }
                    @Override public int    majorVersion()     { return 1; }
                    @Override public int    minorVersion()     { return 0; }
                    @Override public String description()      { return "description"; }
                    @Override public String documentationUrl() { return "https://example.com"; }
                    @Override public List<String> roles()      { return List.of("role_read"); }
                    @Override public Class<JmeDecreeDocumentDataV1> dataClass() { return JmeDecreeDocumentDataV1.class; }

                    @Override public Supplier<InputStream> mappingDefinition() {
                        return () -> getClass().getResourceAsStream("/opensearch/JmeDecreeDocument_mapping_v1_0.json");
                    }
                }
                """;
        assertThat(readSource(outputDir, "JmeDecreeDocumentIndexTypeV1.java")).isEqualTo(expected);
    }

    @Test
    void compatConstructorGeneratedWhenFieldAddedInLaterMinor(@TempDir File outputDir, @TempDir File mappingDir)
            throws IOException, MojoExecutionException {
        File v10 = writeMappingFile(mappingDir, "mapping_v1_0.json", TestRegistryBuilder.VALID_MAPPING_V1_0);
        File v11 = writeMappingFile(mappingDir, "mapping_v1_1.json",
                TestRegistryBuilder.VALID_MAPPING_V1_1_ADDS_FIELD);

        generate(new JavaBeanGenerator(outputDir, BASE_PACKAGE, new SystemStreamLog()),
                "JmeDecreeDocument", "JME", 1, 1, List.of(v10, v11));

        String source = readSource(outputDir, "JmeDecreeDocumentDataV1.java");
        // The latest minor added a field — expect a compat constructor
        assertThat(source).containsPattern("public JmeDecreeDocumentDataV1\\(");
        // The compat constructor should not include the newly added field
        assertThat(source).contains("null)");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String generate(JavaBeanGenerator generator, String typeName, String system,
                             int major, int latestMinor, List<File> allMinors)
            throws MojoExecutionException {
        return generator.generate(typeName, system, "description", "https://example.com",
                List.of("role_read"), major, latestMinor, allMinors.getLast(), allMinors);
    }

    private File writeMappingFile(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }

    private File sourceFile(File outputDir, String system, String indexTypeName, String filename) {
        String pkg = JavaBeanGenerator.packageFor(BASE_PACKAGE, system, indexTypeName);
        return new File(new File(outputDir, pkg.replace('.', '/')), filename);
    }

    private File sourceFile(File outputDir, String filename) {
        return sourceFile(outputDir, "JME", "JmeDecreeDocument", filename);
    }

    private String readSource(File outputDir, String filename) throws IOException {
        return Files.readString(sourceFile(outputDir, filename).toPath());
    }
}
