package ch.admin.bit.jeap.opensearch.registry;

import ch.admin.bit.jeap.opensearch.registry.deploy.IndexTypeArtifactDeployer.IndexTypeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the logic in {@link DeployIndexTypeArtifactsMojo} that does not
 * require a running Maven container. The package-private methods under test are
 * accessed directly from the same package.
 */
class DeployIndexTypeArtifactsMojoTest {

    // ── trunk branch always deploys all ──────────────────────────────────────

    /**
     * Regression test: a new index type added on a feature branch and then merged to trunk
     * must be deployed on the trunk build. Before the fix the plugin cloned trunk for
     * comparison — but since the merge had already landed, trunk already contained the new
     * type, so filterChanged returned an empty list and the artifact was never deployed.
     * The fix skips git comparison on trunk (returning null for masterDescriptorDir), so
     * filterChanged returns all types regardless of what exists on trunk.
     */
    @Test
    void filterChangedReturnsAllOnTrunkEvenWhenMasterAlreadyContainsNewType(
            @TempDir File masterDir, @TempDir File mappingDir) throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "NewType_mapping_v1_0.json", "{}");
        IndexTypeInfo info = info("NewType", "sys", 1, "1.0", List.of(mapping));

        // Simulate trunk already containing the new type (post-merge state)
        File masterTypeDir = new File(masterDir, "sys/newtype");
        Files.createDirectories(masterTypeDir.toPath());
        writeFile(masterTypeDir, "NewType_mapping_v1_0.json", "{}");

        // On trunk, getMasterDescriptorDirForChangeDetection() returns null → all types are deployed
        List<IndexTypeInfo> result = mojo.filterChanged(List.of(info), null);

        assertThat(result).containsExactly(info);
    }

    // ── filterChanged ────────────────────────────────────────────────────────

    @Test
    void filterChangedReturnsAllWhenMasterDirIsNull(@TempDir File mappingDir) throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "MyType_mapping_v1_0.json", "{}");
        IndexTypeInfo info = info("MyType", "sys", 1, "1.0", List.of(mapping));

        List<IndexTypeInfo> result = mojo.filterChanged(List.of(info), null);

        assertThat(result).containsExactly(info);
    }

    @Test
    void filterChangedIncludesNewIndexType(@TempDir File masterDir, @TempDir File mappingDir)
            throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "NewType_mapping_v1_0.json", "{}");
        IndexTypeInfo info = info("NewType", "sys", 1, "1.0", List.of(mapping));

        // masterDir exists but does not contain sys/newtype
        List<IndexTypeInfo> result = mojo.filterChanged(List.of(info), masterDir);

        assertThat(result).containsExactly(info);
    }

    @Test
    void filterChangedExcludesUnchangedIndexType(@TempDir File masterDir, @TempDir File mappingDir)
            throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "MyType_mapping_v1_0.json", "{}");

        // Reproduce the same mapping on master
        File masterTypeDir = new File(masterDir, "sys/mytype");
        Files.createDirectories(masterTypeDir.toPath());
        writeFile(masterTypeDir, "MyType_mapping_v1_0.json", "{}");

        IndexTypeInfo info = info("MyType", "sys", 1, "1.0", List.of(mapping));
        List<IndexTypeInfo> result = mojo.filterChanged(List.of(info), masterDir);

        assertThat(result).isEmpty();
    }

    @Test
    void filterChangedIncludesTypeWithNewMapping(@TempDir File masterDir, @TempDir File mappingDir)
            throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File v10 = writeFile(mappingDir, "MyType_mapping_v1_0.json", "{}");
        File v11 = writeFile(mappingDir, "MyType_mapping_v1_1.json", "{}");  // new minor version

        // Master only has v1_0
        File masterTypeDir = new File(masterDir, "sys/mytype");
        Files.createDirectories(masterTypeDir.toPath());
        writeFile(masterTypeDir, "MyType_mapping_v1_0.json", "{}");

        IndexTypeInfo info = info("MyType", "sys", 1, "1.0", List.of(v10, v11));
        List<IndexTypeInfo> result = mojo.filterChanged(List.of(info), masterDir);

        assertThat(result).containsExactly(info);
    }

    // ── hasChangedSinceMaster ────────────────────────────────────────────────

    @Test
    void newTypeIsAlwaysChanged(@TempDir File masterDir, @TempDir File mappingDir) throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "BrandNew_mapping_v1_0.json", "{}");
        IndexTypeInfo info = info("BrandNew", "sys", 1, "1.0", List.of(mapping));

        assertThat(mojo.hasChangedSinceMaster(info, masterDir)).isTrue();
    }

    @Test
    void existingTypeWithAllMappingsPresentIsUnchanged(@TempDir File masterDir,
                                                        @TempDir File mappingDir)
            throws IOException {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        File mapping = writeFile(mappingDir, "Stable_mapping_v1_0.json", "{}");

        File masterTypeDir = new File(masterDir, "sys/stable");
        Files.createDirectories(masterTypeDir.toPath());
        writeFile(masterTypeDir, "Stable_mapping_v1_0.json", "{}");

        IndexTypeInfo info = info("Stable", "sys", 1, "1.0", List.of(mapping));
        assertThat(mojo.hasChangedSinceMaster(info, masterDir)).isFalse();
    }

    // ── computeArtifactVersion ───────────────────────────────────────────────

    @Test
    void artifactVersionOnTrunkIsMajorDotMinor() {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.trunkBranchName = "master";
        mojo.currentBranch = "master";

        assertThat(mojo.computeArtifactVersion(1, 2)).isEqualTo("1.2");
        assertThat(mojo.computeArtifactVersion(2, 0)).isEqualTo("2.0");
    }

    @Test
    void artifactVersionOnFeatureBranchIncludesSanitizedBranchAndSnapshot() {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.trunkBranchName = "master";
        mojo.currentBranch = "feature/JEAP-6856";

        String version = mojo.computeArtifactVersion(1, 3);

        assertThat(version).startsWith("1.3-");
        assertThat(version).endsWith("-SNAPSHOT");
        assertThat(version).contains("feature-JEAP-6856");
    }

    @Test
    void artifactVersionWhenNoBranchIsSetTreatedAsTrunk() {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.trunkBranchName = "master";
        mojo.currentBranch = null;

        assertThat(mojo.computeArtifactVersion(1, 0)).isEqualTo("1.0");
    }

    // ── resolveEffectivePomTemplate ───────────────────────────────────────────

    @Test
    void resolveEffectivePomTemplateReturnsNullWhenNeitherExplicitNorDefaultPresent(
            @TempDir File descriptorDir) throws Exception {
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.descriptorDirectory = descriptorDir;

        assertThat(mojo.resolveEffectivePomTemplate()).isNull();
    }

    @Test
    void resolveEffectivePomTemplatePicksUpDefaultFilenameFromDescriptorDirectory(
            @TempDir File descriptorDir) throws Exception {
        File defaultTemplate = writeFile(descriptorDir, "indextype.template.pom.xml", "<project/>");
        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.descriptorDirectory = descriptorDir;

        assertThat(mojo.resolveEffectivePomTemplate()).isEqualTo(defaultTemplate);
    }

    @Test
    void resolveEffectivePomTemplateExplicitParameterTakesPrecedenceOverDefaultFile(
            @TempDir File descriptorDir, @TempDir File otherDir) throws Exception {
        writeFile(descriptorDir, "indextype.template.pom.xml", "<project/>");
        File explicitTemplate = writeFile(otherDir, "custom.xml", "<project><extra/></project>");

        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.descriptorDirectory = descriptorDir;
        mojo.pomTemplateFile = explicitTemplate;

        assertThat(mojo.resolveEffectivePomTemplate()).isEqualTo(explicitTemplate);
    }

    // ── index type discovery ──────────────────────────────────────────────────

    @Test
    void discoverIndexTypesBuildsInfoPerMajor(@TempDir File tempDir) throws Exception {
        File descriptorDir = TestRegistryBuilder.mkdirs(tempDir, "index-types");
        File outputDir = new File(tempDir, "generated-sources");
        File outputResourcesDir = new File(tempDir, "classes");
        File typeDir = TestRegistryBuilder.mkdirs(descriptorDir, "jme", "jmedecreedocument");

        TestRegistryBuilder.write(typeDir, "JmeDecreeDocument.json", """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Test",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" },
                    { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json" }
                  ]
                }
                """);

        File packageDir = TestRegistryBuilder.mkdirs(outputDir, "ch", "admin", "bit", "test", "index", "jme", "decreedocument");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentDataV1.java", "class JmeDecreeDocumentDataV1 {}\n");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentIndexTypeV1.java", "class JmeDecreeDocumentIndexTypeV1 {}\n");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentDataV2.java", "class JmeDecreeDocumentDataV2 {}\n");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentIndexTypeV2.java", "class JmeDecreeDocumentIndexTypeV2 {}\n");

        File mappingsDir = TestRegistryBuilder.mkdirs(outputResourcesDir, "opensearch");
        TestRegistryBuilder.write(mappingsDir, "JmeDecreeDocument_mapping_v1_0.json", "{}");
        TestRegistryBuilder.write(mappingsDir, "JmeDecreeDocument_mapping_v1_1.json", "{}");
        TestRegistryBuilder.write(mappingsDir, "JmeDecreeDocument_mapping_v2_0.json", "{}");

        File metaInfDir = TestRegistryBuilder.mkdirs(outputResourcesDir, "META-INF");
        TestRegistryBuilder.write(metaInfDir, "index-types.json", """
                {
                  "indexTypes": [
                    {
                      "indexTypeName": "JmeDecreeDocument",
                      "system": "JME",
                      "roles": ["jme_read"],
                      "mappingVersions": [
                        { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json", "beanClassName": "x.V1" },
                        { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json", "beanClassName": "x.V1" },
                        { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json", "beanClassName": "x.V2" }
                      ]
                    }
                  ]
                }
                """);

        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.descriptorDirectory = descriptorDir;
        mojo.outputDirectory = outputDir;
        mojo.outputResourcesDirectory = outputResourcesDir;
        mojo.basePackage = "ch.admin.bit.test.index";

        List<IndexTypeInfo> infos = mojo.discoverIndexTypes();

        assertThat(infos).hasSize(2);
        assertThat(infos).extracting(IndexTypeInfo::majorVersion).containsExactly(1, 2);
        assertThat(infos).extracting(IndexTypeInfo::artifactVersion).containsExactly("1.1", "2.0");
        assertThat(infos.getFirst().mappingFiles()).extracting(File::getName)
                .containsExactlyInAnyOrder("JmeDecreeDocument_mapping_v1_0.json", "JmeDecreeDocument_mapping_v1_1.json");
    }

    @Test
    void discoverIndexTypesSkipsMajorWithoutGeneratedSources(@TempDir File tempDir) throws Exception {
        File descriptorDir = TestRegistryBuilder.mkdirs(tempDir, "index-types");
        File outputDir = new File(tempDir, "generated-sources");
        File outputResourcesDir = new File(tempDir, "classes");
        File typeDir = TestRegistryBuilder.mkdirs(descriptorDir, "jme", "jmedecreedocument");

        TestRegistryBuilder.write(typeDir, "JmeDecreeDocument.json", """
                {
                  "system": "JME",
                  "originType": "JmeDecreeDocument",
                  "description": "Test",
                  "roles": ["jme_read"],
                  "mappingVersions": [
                    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
                    { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json" }
                  ]
                }
                """);

        File packageDir = TestRegistryBuilder.mkdirs(outputDir, "ch", "admin", "bit", "test", "index", "jme", "decreedocument");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentDataV1.java", "class JmeDecreeDocumentDataV1 {}\n");
        TestRegistryBuilder.write(packageDir, "JmeDecreeDocumentIndexTypeV1.java", "class JmeDecreeDocumentIndexTypeV1 {}\n");

        File mappingsDir = TestRegistryBuilder.mkdirs(outputResourcesDir, "opensearch");
        TestRegistryBuilder.write(mappingsDir, "JmeDecreeDocument_mapping_v1_0.json", "{}");
        TestRegistryBuilder.write(mappingsDir, "JmeDecreeDocument_mapping_v2_0.json", "{}");

        File metaInfDir = TestRegistryBuilder.mkdirs(outputResourcesDir, "META-INF");
        TestRegistryBuilder.write(metaInfDir, "index-types.json", """
                {
                  "indexTypes": [
                    {
                      "indexTypeName": "JmeDecreeDocument",
                      "system": "JME",
                      "roles": ["jme_read"],
                      "mappingVersions": [
                        { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json", "beanClassName": "x.V1" },
                        { "major": 2, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v2_0.json", "beanClassName": "x.V2" }
                      ]
                    }
                  ]
                }
                """);

        DeployIndexTypeArtifactsMojo mojo = new DeployIndexTypeArtifactsMojo();
        mojo.descriptorDirectory = descriptorDir;
        mojo.outputDirectory = outputDir;
        mojo.outputResourcesDirectory = outputResourcesDir;
        mojo.basePackage = "ch.admin.bit.test.index";

        List<IndexTypeInfo> infos = mojo.discoverIndexTypes();

        assertThat(infos).hasSize(1);
        assertThat(infos.getFirst().majorVersion()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static IndexTypeInfo info(String typeName, String system, int major,
                                       String version, List<File> mappingFiles) {
        String fqn = "ch.admin.bit.test." + typeName + "IndexTypeV" + major;
        return new IndexTypeInfo(typeName, system, major, version, fqn, List.of(), mappingFiles, null);
    }

    private static File writeFile(File dir, String name, String content) throws IOException {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), content);
        return f;
    }
}
