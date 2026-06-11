package ch.admin.bit.jeap.opensearch.registry;

import ch.admin.bit.jeap.opensearch.registry.deploy.IndexTypeArtifactDeployer;
import ch.admin.bit.jeap.opensearch.registry.deploy.IndexTypeArtifactDeployer.IndexTypeInfo;
import ch.admin.bit.jeap.opensearch.registry.generator.JavaBeanGenerator;
import ch.admin.bit.jeap.opensearch.registry.git.GitClient;
import ch.admin.bit.jeap.opensearch.registry.git.GitClientException;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static ch.admin.bit.jeap.opensearch.registry.RegistryConstants.MAPPING_VERSIONS;
import static org.springframework.util.StringUtils.hasText;

/**
 * Deploys each index type as an individual Maven artifact.
 * <p>
 * For each index type found in the descriptor directory, this goal:
 * <ol>
 *   <li>Compiles the generated Java sources for that index type</li>
 *   <li>Packages them into a JAR together with the mapping JSON files and a
 *       per-type {@code META-INF/index-types.json}</li>
 *   <li>Deploys the JAR via {@code install:install-file} or {@code deploy:deploy-file}</li>
 * </ol>
 * <p>
 * The goal is idempotent: if an artifact already exists in the repository (HTTP 409),
 * it logs a warning and continues with the remaining index types.
 * <p>
 * When {@code deployAllIndexTypes=false} (the default) and a {@code gitUrl} is configured,
 * only index types that have new or changed mapping files compared to the trunk branch
 * are deployed on feature branches. On the trunk branch all index types are always
 * deployed: comparing against trunk after a merge would find no changes because the
 * merge has already landed, so newly added types would be silently skipped.
 */
@Mojo(name = "deploy-index-type-artifacts", defaultPhase = LifecyclePhase.DEPLOY)
public class DeployIndexTypeArtifactsMojo extends AbstractMojo {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    /**
     * {@code install} to install to the local repository, {@code deploy} to deploy to a remote repository.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "mavenDeployGoal", defaultValue = "deploy")
    private String mavenDeployGoal;

    private static final String DEFAULT_POM_TEMPLATE_FILENAME = "indextype.template.pom.xml";

    @SuppressWarnings("unused")
    @Parameter(name = "pomTemplateFile")
    File pomTemplateFile;

    /**
     * Path to a Maven settings file passed via {@code --settings} to the invoked Maven command.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "mavenGlobalSettingsFile")
    private String mavenGlobalSettingsFile;

    /**
     * GroupId prefix for the generated per-type artifacts, e.g. {@code ch.admin.bit.jme.indextype}.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "groupIdPrefix", required = true)
    private String groupIdPrefix;

    @Parameter(name = "mavenExecutable")
    @SuppressWarnings("unused")
    private String mavenExecutable;

    @SuppressWarnings("unused")
    @Parameter(name = "skip", defaultValue = "false")
    private boolean skip;

    /**
     * When {@code true}, all index types are deployed regardless of changes.
     * When {@code false} (the default), only index types that have new or modified mapping files
     * compared to the trunk branch are deployed. Requires {@code gitUrl} to be set; if
     * {@code gitUrl} is blank, this flag is ignored and all index types are deployed.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "deployAllIndexTypes", defaultValue = "false")
    private boolean deployAllIndexTypes;

    /**
     * Git URL of the registry repository, used to clone the trunk branch for change detection.
     * Leave blank to deploy all index types unconditionally.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "gitUrl")
    private String gitUrl;

    /**
     * Name of the environment variable holding the Git token for cloning the registry.
     * Falls back to unauthenticated system git if the variable is not set.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "gitTokenEnvVariableName", defaultValue = "INDEX_TYPE_REPO_GIT_TOKEN")
    private String gitTokenEnvVariableName;

    @SuppressWarnings("unused")
    @Parameter(name = "trunkBranchName", defaultValue = "master")
    String trunkBranchName;

    /**
     * The current Git branch, injected from the {@code git.branch} property provided by
     * {@code git-commit-id-maven-plugin}. When set and equal to {@code trunkBranchName},
     * {@code trunkMavenProfile} is activated in the Maven deploy invocation.
     * On feature branches the profile is not activated, so artifacts deploy to the
     * snapshot repository instead of the releases repository.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "currentBranch", defaultValue = "${git.branch}")
    String currentBranch;

    /**
     * Maven profile to activate when deploying from the trunk branch, e.g. a profile that
     * configures the releases repository credentials. Not activated on feature branches,
     * which causes artifacts to be deployed to the snapshot repository instead.
     */
    @SuppressWarnings("unused")
    @Parameter(name = "trunkMavenProfile")
    String trunkMavenProfile;

    @SuppressWarnings("unused")
    @Getter(AccessLevel.PROTECTED)
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @SuppressWarnings("unused")
    @Parameter(name = "descriptorDirectory", defaultValue = "${basedir}/index-types")
    File descriptorDirectory;

    @SuppressWarnings("unused")
    @Parameter(name = "basePackage", required = true)
    String basePackage;

    @SuppressWarnings("unused")
    @Parameter(name = "indexTypeVersion", required = true)
    String indexTypeVersion;

    @SuppressWarnings("unused")
    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/index-type-registry")
    File outputDirectory;

    @SuppressWarnings("unused")
    @Parameter(name = "outputResourcesDirectory", defaultValue = "${project.build.outputDirectory}")
    File outputResourcesDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping deploy-index-type-artifacts (skip=true).");
            return;
        }

        String activeProfile = isBuildOnTrunk() ? trunkMavenProfile : null;
        if (!isBuildOnTrunk()) {
            getLog().info("Not on trunk branch '%s' (current: '%s') — deploying to snapshot repository (trunkMavenProfile not activated)."
                    .formatted(trunkBranchName, currentBranch));
        }

        List<IndexTypeInfo> indexTypes = discoverIndexTypes();
        if (indexTypes.isEmpty()) {
            getLog().warn("No index types found in " + descriptorDirectory);
            return;
        }

        File masterDescriptorDir = getMasterDescriptorDirForChangeDetection();
        List<IndexTypeInfo> toDeployList = filterChanged(indexTypes, masterDescriptorDir);

        if (toDeployList.isEmpty()) {
            getLog().info("No changed index types to deploy.");
            return;
        }

        getLog().info("Deploying %d index type artifact(s) with goal '%s'"
                .formatted(toDeployList.size(), mavenDeployGoal));

        IndexTypeArtifactDeployer deployer = new IndexTypeArtifactDeployer(
                mavenDeployGoal, mavenGlobalSettingsFile, mavenExecutable, activeProfile,
                groupIdPrefix, indexTypeVersion, outputDirectory, resolveEffectivePomTemplate(),
                project, getLog());

        for (IndexTypeInfo info : toDeployList) {
            deployer.deploy(info);
        }
    }

    /**
     * Resolves the POM template to use. Resolution order:
     * <ol>
     *   <li>Explicit {@code pomTemplateFile} parameter</li>
     *   <li>Auto-detected {@value DEFAULT_POM_TEMPLATE_FILENAME} in {@code descriptorDirectory}</li>
     *   <li>{@code null} — falls back to the built-in classpath template</li>
     * </ol>
     */
    File resolveEffectivePomTemplate() {
        if (pomTemplateFile != null) {
            return pomTemplateFile;
        }
        File defaultTemplate = new File(descriptorDirectory, DEFAULT_POM_TEMPLATE_FILENAME);
        if (defaultTemplate.exists()) {
            getLog().info("Using POM template from registry: " + defaultTemplate.getAbsolutePath());
            return defaultTemplate;
        }
        return null;
    }

    /**
     * Clones the trunk branch for change detection on feature branches.
     * Returns {@code null} (deploy all) when: {@code deployAllIndexTypes=true},
     * {@code gitUrl} is blank, or the build is on the trunk branch itself.
     * The trunk case is skipped because after a merge trunk already contains the
     * new type, so a comparison would find no difference and newly added types
     * would never be deployed.
     */
    @SuppressWarnings("java:S5443")
    private File getMasterDescriptorDirForChangeDetection() throws MojoExecutionException {
        if (deployAllIndexTypes || !hasText(gitUrl) || isBuildOnTrunk()) {
            if (isBuildOnTrunk() && !deployAllIndexTypes && hasText(gitUrl)) {
                getLog().info("On trunk branch — deploying all index types (git comparison skipped to ensure newly added types are deployed).");
            }
            return null;
        }
        try {
            File tempDir = Files.createTempDirectory(trunkBranchName).toFile();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (var paths = Files.walk(tempDir.toPath())) {
                    paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                } catch (IOException _) {
                    // do ignore
                }
            }));
            getLog().info("Cloning '%s' branch '%s' for change detection...".formatted(gitUrl, trunkBranchName));
            GitClient gitClient = new GitClient(gitUrl, gitTokenEnvVariableName, getLog());
            gitClient.cloneAtBranch(trunkBranchName, tempDir);
            return new File(tempDir, descriptorDirectory.getName());
        } catch (IOException | GitClientException e) {
            throw new MojoExecutionException("Cannot clone trunk branch for change detection", e);
        }
    }

    /**
     * Filters the list of index types, keeping only those that have changed since master.
     * An index type is considered changed if:
     * <ul>
     *   <li>It is a new type (its directory does not exist on master), or</li>
     *   <li>It has at least one mapping file that does not exist in the master directory.</li>
     * </ul>
     * If {@code masterDescriptorDir} is {@code null}, all index types are returned.
     */
    List<IndexTypeInfo> filterChanged(List<IndexTypeInfo> indexTypes, File masterDescriptorDir) {
        if (masterDescriptorDir == null) {
            return indexTypes;
        }
        List<IndexTypeInfo> changed = new ArrayList<>();
        for (IndexTypeInfo info : indexTypes) {
            if (hasChangedSinceMaster(info, masterDescriptorDir)) {
                changed.add(info);
            } else {
                getLog().info("Skipping unchanged index type: " + info.indexTypeName() + " v" + info.majorVersion());
            }
        }
        return changed;
    }

    boolean hasChangedSinceMaster(IndexTypeInfo info, File masterDescriptorDir) {
        File masterTypeDir = new File(masterDescriptorDir,
                info.systemName() + "/" + info.indexTypeName().toLowerCase());
        if (!masterTypeDir.isDirectory()) {
            // New index type — always deploy
            return true;
        }
        // Check if any local mapping file is absent on master
        for (File mappingFile : info.mappingFiles()) {
            File masterMapping = new File(masterTypeDir, mappingFile.getName());
            if (!masterMapping.exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuildOnTrunk() {
        return currentBranch == null || trunkBranchName.equals(currentBranch);
    }

    // -------------------------------------------------------------------------
    // Index type discovery
    // -------------------------------------------------------------------------

    List<IndexTypeInfo> discoverIndexTypes() {
        List<IndexTypeInfo> result = new ArrayList<>();
        if (!descriptorDirectory.isDirectory()) return result;

        List<JsonNode> allEntries = readIndexTypesJson(
                new File(outputResourcesDirectory, "META-INF/index-types.json"));

        String[] systemNames = descriptorDirectory.list();
        if (systemNames == null) return result;

        for (String systemName : systemNames) {
            File systemDir = new File(descriptorDirectory, systemName);
            if (!systemDir.isDirectory()) continue;
            String[] indexTypeDirs = systemDir.list();
            if (indexTypeDirs == null) continue;
            for (String dirName : indexTypeDirs) {
                File indexTypeDir = new File(systemDir, dirName);
                if (!indexTypeDir.isDirectory()) continue;
                result.addAll(buildInfosPerMajorVersion(systemName, indexTypeDir, allEntries));
            }
        }
        return result;
    }

    private List<IndexTypeInfo> buildInfosPerMajorVersion(String systemName, File indexTypeDir, List<JsonNode> allEntries) {
        String indexTypeName = findIndexTypeName(indexTypeDir);
        if (indexTypeName == null) return List.of();

        JsonNode descriptor = readDescriptor(indexTypeDir, indexTypeName);
        if (descriptor == null) return List.of();

        // Group mapping versions by major
        Map<Integer, Integer> latestMinorByMajor = new TreeMap<>();
        JsonNode mappingVersions = descriptor.path(MAPPING_VERSIONS);
        if (mappingVersions.isArray()) {
            for (JsonNode v : mappingVersions) {
                int major = v.path("major").asInt();
                int minor = v.path("minor").asInt();
                latestMinorByMajor.merge(major, minor, Math::max);
            }
        }

        File packageDir = new File(outputDirectory,
                JavaBeanGenerator.packageFor(basePackage, systemName, indexTypeName).replace('.', '/'));
        File mappingsDir = new File(outputResourcesDirectory, "opensearch");
        JsonNode fullEntry = allEntries.stream()
                .filter(e -> indexTypeName.equals(e.path("indexTypeName").asString()))
                .findFirst().orElse(null);

        List<IndexTypeInfo> result = new ArrayList<>();
        for (Map.Entry<Integer, Integer> majorEntry : latestMinorByMajor.entrySet()) {
            int major = majorEntry.getKey();
            int latestMinor = majorEntry.getValue();

            List<File> sourceFiles = new ArrayList<>();
            File dataFile = new File(packageDir, indexTypeName + "DataV" + major + ".java");
            File indexTypeFile = new File(packageDir, indexTypeName + "IndexTypeV" + major + ".java");
            if (dataFile.exists()) {
                sourceFiles.add(dataFile);
            }
            if (indexTypeFile.exists()) {
                sourceFiles.add(indexTypeFile);
            }

            if (sourceFiles.isEmpty()) {
                getLog().warn("No generated sources for '%s' v%d, skipping.".formatted(indexTypeName, major));
                continue;
            }

            List<File> mappingFiles = new ArrayList<>();
            File[] mappings = mappingsDir.listFiles((d, n) ->
                    n.matches(indexTypeName + "_mapping_v" + major + "_\\d+\\.json"));
            if (mappings != null) {
                mappingFiles.addAll(List.of(mappings));
            }

            JsonNode filteredEntry = filterEntryForMajor(fullEntry, major);
            String artifactVersion = computeArtifactVersion(major, latestMinor);
            String indexTypeFqn = JavaBeanGenerator.packageFor(basePackage, systemName, indexTypeName)
                    + "." + indexTypeName + "IndexTypeV" + major;

            result.add(new IndexTypeInfo(indexTypeName, systemName, major, artifactVersion,
                    indexTypeFqn, sourceFiles, mappingFiles, filteredEntry));
        }
        return result;
    }

    private JsonNode readDescriptor(File indexTypeDir, String indexTypeName) {
        File descriptorFile = new File(indexTypeDir, indexTypeName + ".json");
        if (!descriptorFile.exists()) {
            return null;
        }
        return JSON_MAPPER.readTree(descriptorFile);
    }

    private JsonNode filterEntryForMajor(JsonNode entry, int major) {
        if (entry == null) {
            return null;
        }
        ObjectNode filtered = ((ObjectNode) entry.deepCopy());
        ArrayNode filteredVersions = JSON_MAPPER.createArrayNode();
        filtered.set(MAPPING_VERSIONS, filteredVersions);
        JsonNode versions = entry.path(MAPPING_VERSIONS);
        if (versions.isArray()) {
            for (JsonNode v : versions) {
                if (v.path("major").asInt() == major) {
                    filteredVersions.add(v);
                }
            }
        }
        return filtered;
    }

    String computeArtifactVersion(int major, int latestMinor) {
        String base = major + "." + latestMinor;
        if (isBuildOnTrunk()) {
            return base;
        }
        return base + "-" + getSanitizedCurrentBranchName() + "-SNAPSHOT";
    }

    private String getSanitizedCurrentBranchName() {
        if (!hasText(currentBranch)) {
            return "local";
        }
        return currentBranch.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String findIndexTypeName(File indexTypeDir) {
        String[] files = indexTypeDir.list((d, n) ->
                n.endsWith(".json") && !n.contains("_mapping_v"));
        if (files == null || files.length == 0) {
            return null;
        }
        String name = files[0];
        return name.substring(0, name.length() - 5); // strip .json
    }

    /**
     * Reads the flat list of index type entries from the {@code indexTypes} array in the JSON file.
     */
    private List<JsonNode> readIndexTypesJson(File file) {
        if (!file.exists()) return List.of();
        List<JsonNode> entries = new ArrayList<>();
        JsonNode root = JSON_MAPPER.readTree(file);
        JsonNode array = root.path("indexTypes");
        if (array.isArray()) array.forEach(entries::add);
        return entries;
    }
}
