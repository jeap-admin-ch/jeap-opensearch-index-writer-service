package ch.admin.bit.jeap.opensearch.registry;

import ch.admin.bit.jeap.opensearch.registry.generator.JavaBeanGenerator;
import ch.admin.bit.jeap.opensearch.registry.generator.MetaInfIndexTypeWriter;
import ch.admin.bit.jeap.opensearch.registry.generator.MetaInfIndexTypeWriter.IndexTypeEntry;
import ch.admin.bit.jeap.opensearch.registry.generator.MetaInfIndexTypeWriter.MappingVersionEntry;
import ch.admin.bit.jeap.opensearch.registry.git.GitClient;
import ch.admin.bit.jeap.opensearch.registry.git.GitClientException;
import ch.admin.bit.jeap.opensearch.registry.verifier.DescriptorDirectoryValidator;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationContext;
import ch.admin.bit.jeap.opensearch.registry.verifier.ValidationResult;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.springframework.util.StringUtils;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_DEFINITION;

/**
 * Maven plugin goal that validates an OpenSearch index type registry and generates
 * Java bean classes and metadata.
 * <p>
 * The registry is expected to follow this directory structure:
 * <pre>
 * index-types/
 *   &lt;system&gt;/                          (lowercase system name, e.g. "jme")
 *     &lt;indextypename&gt;/                 (lowercase, e.g. "jmedecreedocument")
 *       &lt;IndexTypeName&gt;.json           (descriptor, PascalCase)
 *       &lt;IndexTypeName&gt;_mapping_v1_0.json  (mapping files, one per version)
 * </pre>
 * <p>
 * Validation includes:
 * <ul>
 *   <li>JSON schema validation of descriptor and mapping files</li>
 *   <li>Naming convention checks</li>
 *   <li>No deletions of index types or systems compared to master branch</li>
 *   <li>Minor version backward compatibility (no properties removed)</li>
 * </ul>
 * <p>
 * Runs in the {@code verify} phase, after compile and test but before deploy.
 * <p>
 * Generation includes:
 * <ul>
 *   <li>Java bean classes implementing {@code SearchItem} for each (indexType, majorVersion)</li>
 *   <li>{@code META-INF/index-types.json} metadata file</li>
 *   <li>Copies mapping JSON files to {@code META-INF/index-type-mappings/}</li>
 * </ul>
 */
@Mojo(name = "registry", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class IndexTypeRegistryMojo extends AbstractMojo {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @SuppressWarnings("unused")
    @Getter(AccessLevel.PROTECTED)
    @Parameter(name = "descriptorDirectory", defaultValue = "${basedir}/index-types")
    private File descriptorDirectory;

    @SuppressWarnings("unused")
    @Parameter(name = "gitUrl")
    private String gitUrl;

    @SuppressWarnings("unused")
    @Parameter(name = "gitTokenEnvVariableName", defaultValue = "INDEX_TYPE_REPO_GIT_TOKEN")
    private String gitTokenEnvVariableName;

    @SuppressWarnings("unused")
    @Parameter(defaultValue = "master")
    private String trunkBranchName;

    @SuppressWarnings("unused")
    @Getter(AccessLevel.PROTECTED)
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @SuppressWarnings("unused")
    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/index-type-registry")
    private File outputDirectory;

    @SuppressWarnings("unused")
    @Parameter(name = "outputResourcesDirectory", defaultValue = "${project.build.outputDirectory}")
    private File outputResourcesDirectory;

    @SuppressWarnings("unused")
    @Parameter(name = "basePackage", required = true)
    private String basePackage;

    @SuppressWarnings("unused")
    @Parameter(name = "skipGeneration", defaultValue = "false")
    private boolean skipGeneration;

    @Override
    public void execute() throws MojoExecutionException {
        // 1. Validate the registry
        ValidationContext validationContext = ValidationContext.builder()
                .descriptorDir(descriptorDirectory)
                .oldDescriptorDir(getMasterDescriptorDir())
                .build();

        ValidationResult result = DescriptorDirectoryValidator.validate(validationContext);
        if (!result.isValid()) {
            result.getErrors().forEach(getLog()::error);
            throw new MojoExecutionException(
                    "The index type registry is not valid (%d error(s)). Check log for details.\n%s"
                            .formatted(result.getErrors().size(), String.join("\n", result.getErrors())));
        }

        if (skipGeneration) {
            getLog().info("Skipping code generation (skipGeneration=true).");
            return;
        }

        // 2. Generate Java beans and META-INF
        generateSources();
    }

    @SuppressWarnings("java:S5443")
    private File getMasterDescriptorDir() throws MojoExecutionException {
        if (!StringUtils.hasText(gitUrl)) {
            getLog().warn("No gitUrl configured. Skipping comparison with master branch.");
            return descriptorDirectory;
        }
        try {
            File tempDir = Files.createTempDirectory(trunkBranchName).toFile();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var paths = Files.walk(tempDir.toPath())) {
                    paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                } catch (IOException _) {
                }
            }));
            getLog().info("Cloning '%s' branch '%s' for comparison...".formatted(gitUrl, trunkBranchName));
            GitClient gitClient = new GitClient(gitUrl, gitTokenEnvVariableName, getLog());
            gitClient.cloneAtBranch(trunkBranchName, tempDir);
            return new File(tempDir, descriptorDirectory.getName());
        } catch (IOException | GitClientException e) {
            throw new MojoExecutionException("Cannot clone master branch for comparison", e);
        }
    }

    private void generateSources() throws MojoExecutionException {
        JavaBeanGenerator beanGenerator = new JavaBeanGenerator(outputDirectory, basePackage, getLog());

        MetaInfIndexTypeWriter metaInfWriter = new MetaInfIndexTypeWriter(outputResourcesDirectory, getLog());
        List<IndexTypeEntry> indexTypeEntries = new ArrayList<>();

        if (!descriptorDirectory.exists() || !descriptorDirectory.isDirectory()) {
            return;
        }

        String[] systemNames = descriptorDirectory.list();
        if (systemNames == null) {
            return;
        }

        for (String systemName : systemNames) {
            File systemDir = new File(descriptorDirectory, systemName);
            if (!systemDir.isDirectory()) {
                continue;
            }
            String[] indexTypeDirs = systemDir.list();
            if (indexTypeDirs == null) {
                continue;
            }
            for (String indexTypeDirName : indexTypeDirs) {
                File indexTypeDir = new File(systemDir, indexTypeDirName);
                if (!indexTypeDir.isDirectory()) {
                    continue;
                }
                IndexTypeEntry entry = processIndexType(beanGenerator, systemName, indexTypeDir);
                if (entry != null) {
                    indexTypeEntries.add(entry);
                }
            }
        }

        metaInfWriter.write(indexTypeEntries);

        // Add generated sources to compile roots
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        getLog().info("Added generated sources directory: " + outputDirectory);
    }

    private IndexTypeEntry processIndexType(JavaBeanGenerator beanGenerator,
                                            String systemName, File indexTypeDir) throws MojoExecutionException {
        String[] jsonFiles = indexTypeDir.list((dir, name) -> name.endsWith(".json") && !name.contains("_mapping_v"));
        if (jsonFiles == null || jsonFiles.length == 0) {
            return null;
        }
        String descriptorFilename = jsonFiles[0];
        String indexTypeName = descriptorFilename.substring(0, descriptorFilename.length() - 5);
        File descriptorFile = new File(indexTypeDir, descriptorFilename);

        JsonNode descriptor;
        try {
            descriptor = JSON_MAPPER.readTree(descriptorFile);
        } catch (JacksonIOException e) {
            throw new MojoExecutionException("Cannot read descriptor: " + descriptorFile, e);
        }

        String system = descriptor.path("system").asString("");
        String description = descriptor.path("description").asString("");
        String documentationUrl = descriptor.path("documentationUrl").asString("");
        List<String> roles = MetaInfIndexTypeWriter.extractRoles(descriptorFile);

        JsonNode mappingVersions = descriptor.path("mappingVersions");
        if (!mappingVersions.isArray()) {
            return null;
        }

        // Group all mapping version nodes by major
        java.util.TreeMap<Integer, List<JsonNode>> byMajor = new java.util.TreeMap<>();
        for (JsonNode v : mappingVersions) {
            int major = v.path("major").asInt();
            byMajor.computeIfAbsent(major, k -> new ArrayList<>()).add(v);
        }

        List<MappingVersionEntry> versionEntries = new ArrayList<>();
        for (Map.Entry<Integer, List<JsonNode>> majorEntry : byMajor.entrySet()) {
            int major = majorEntry.getKey();
            List<JsonNode> minors = majorEntry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(v -> v.path("minor").asInt()))
                    .toList();

            int latestMinor = minors.getLast().path("minor").asInt();

            // Collect all minor mapping files for this major, sorted ascending
            List<File> allMinorFiles = minors.stream()
                    .map(v -> new File(indexTypeDir, v.path(MAPPING_DEFINITION).asString()))
                    .toList();
            File latestMappingFile = allMinorFiles.getLast();

            String indexTypeFqn = beanGenerator.generate(
                    indexTypeName, system, description, documentationUrl, roles,
                    major, latestMinor, latestMappingFile, allMinorFiles);

            for (JsonNode v : minors) {
                int minor = v.path("minor").asInt();
                String mappingDefinition = v.path(MAPPING_DEFINITION).asString();
                versionEntries.add(new MappingVersionEntry(major, minor, mappingDefinition, indexTypeFqn));
            }
        }

        return new IndexTypeEntry(indexTypeName, system, roles, versionEntries, indexTypeDir);
    }
}
