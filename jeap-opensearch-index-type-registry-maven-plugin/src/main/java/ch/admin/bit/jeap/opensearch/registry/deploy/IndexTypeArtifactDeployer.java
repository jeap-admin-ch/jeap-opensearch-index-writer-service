package ch.admin.bit.jeap.opensearch.registry.deploy;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.*;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class IndexTypeArtifactDeployer {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private static final List<String> ALREADY_EXISTS_PATTERNS = List.of(
            "409", "already exists", "artifact already deployed",
            "already been deployed", "status code: 409", "ReasonPhrase:Conflict");

    private final String mavenDeployGoal;
    private final String mavenGlobalSettingsFile;
    private final String mavenExecutable;
    private final String mavenProfile;
    private final String groupIdPrefix;
    private final String indexTypeVersion;
    private final File outputDirectory;
    private final File pomTemplateFile;
    private final MavenProject project;
    private final Log log;

    public IndexTypeArtifactDeployer(String mavenDeployGoal, String mavenGlobalSettingsFile,
                                     String mavenExecutable, String mavenProfile,
                                     String groupIdPrefix, String indexTypeVersion,
                                     File outputDirectory, File pomTemplateFile,
                                     MavenProject project, Log log) {
        this.mavenDeployGoal = mavenDeployGoal;
        this.mavenGlobalSettingsFile = mavenGlobalSettingsFile;
        this.mavenExecutable = mavenExecutable;
        this.mavenProfile = mavenProfile;
        this.groupIdPrefix = groupIdPrefix;
        this.indexTypeVersion = indexTypeVersion;
        this.outputDirectory = outputDirectory;
        this.pomTemplateFile = pomTemplateFile;
        this.project = project;
        this.log = log;
    }

    public record IndexTypeInfo(String indexTypeName, String systemName, int majorVersion,
                                String artifactVersion, String indexTypeFqn,
                                List<File> sourceFiles, List<File> mappingFiles,
                                JsonNode indexTypesEntry) {
    }

    public void deploy(IndexTypeInfo info) throws MojoExecutionException {
        String groupId = groupIdPrefix + "." + info.systemName().toLowerCase();
        String artifactId = toKebabCase(info.indexTypeName()) + "-v" + info.majorVersion();
        String version = info.artifactVersion();
        log.info("Deploying: %s:%s:%s".formatted(groupId, artifactId, version));

        File tempDir = createTempDir(info.indexTypeName());
        try {
            setupProjectStructure(info, artifactId, tempDir);
            File pomFile = generatePomFile(groupId, artifactId, version, indexTypeVersion, tempDir);
            invokeMaven(pomFile, groupId, artifactId, version);
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private static final String INDEX_TYPE_SERVICE_FILE =
            "META-INF/services/ch.admin.bit.jeap.opensearch.indextype.IndexType";

    private void setupProjectStructure(IndexTypeInfo info, String artifactId, File tempDir)
            throws MojoExecutionException {
        try {
            copySourceFiles(info.sourceFiles(), tempDir);
            copyMappingFiles(info.mappingFiles(), tempDir);
            if (info.indexTypesEntry() != null) {
                writeIndexTypesJson(artifactId, info.indexTypesEntry(), tempDir);
            }
            if (info.indexTypeFqn() != null && !info.indexTypeFqn().isBlank()) {
                writeServicesFile(info.indexTypeFqn(), tempDir);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot set up project structure for " + artifactId, e);
        }
    }

    private void writeServicesFile(String indexTypeFqn, File tempDir) throws IOException {
        Path target = tempDir.toPath().resolve("src/main/resources").resolve(INDEX_TYPE_SERVICE_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, indexTypeFqn + "\n");
    }

    private void copySourceFiles(List<File> sourceFiles, File tempDir) throws IOException {
        for (File sourceFile : sourceFiles) {
            Path relativePath = outputDirectory.toPath().relativize(sourceFile.toPath());
            Path target = tempDir.toPath().resolve("src/main/java").resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.copy(sourceFile.toPath(), target);
        }
    }

    private void copyMappingFiles(List<File> mappingFiles, File tempDir) throws IOException {
        Path resourcesDir = tempDir.toPath().resolve("src/main/resources/opensearch");
        Files.createDirectories(resourcesDir);
        for (File mappingFile : mappingFiles) {
            Files.copy(mappingFile.toPath(), resourcesDir.resolve(mappingFile.getName()));
        }
    }

    private void writeIndexTypesJson(String artifactId, JsonNode entry, File tempDir)
            throws IOException {
        ObjectNode root = JSON_MAPPER.createObjectNode();
        ArrayNode indexTypesArray = JSON_MAPPER.createArrayNode();
        indexTypesArray.add(entry);
        root.set("indexTypes", indexTypesArray);
        Path target = tempDir.toPath().resolve("src/main/resources/META-INF/index-types.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private File generatePomFile(String groupId, String artifactId, String version,
                                 String indexTypeVersion, File tempDir) throws MojoExecutionException {
        File pomFile = new File(tempDir, "pom.xml");
        try {
            String templateContent = readPomTemplate();
            String xml = templateContent
                    .replace("${groupId}", groupId)
                    .replace("${artifactId}", artifactId)
                    .replace("${version}", version)
                    .replace("${indexTypeVersion}", indexTypeVersion)
                    .replace("${javaRelease}", getJavaRelease());
            Files.writeString(pomFile.toPath(), xml);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write pom.xml for " + artifactId, e);
        }
        return pomFile;
    }

    private String readPomTemplate() throws IOException, MojoExecutionException {
        if (pomTemplateFile != null) {
            if (!pomTemplateFile.exists()) {
                throw new MojoExecutionException("Custom POM template not found: " + pomTemplateFile.getAbsolutePath());
            }
            log.info("Using custom POM template: " + pomTemplateFile.getAbsolutePath());
            return Files.readString(pomTemplateFile.toPath());
        }
        try (var in = getClass().getClassLoader().getResourceAsStream("pom.template.indextype.xml")) {
            if (in == null) {
                throw new MojoExecutionException("pom.template.indextype.xml not found on classpath");
            }
            return new String(in.readAllBytes());
        }
    }

    private void invokeMaven(File pomFile, String groupId, String artifactId, String version) throws MojoExecutionException {
        boolean isInstall = "install".equals(mavenDeployGoal);
        String goal = isInstall ? "install" : "deploy";

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBatchMode(true);
        request.setPomFile(pomFile);
        request.setMavenExecutable(resolveMavenExecutable());
        request.addArg(goal);

        if (mavenGlobalSettingsFile != null && !mavenGlobalSettingsFile.isBlank()) {
            File sf = new File(mavenGlobalSettingsFile);
            if (!sf.isAbsolute()) {
                sf = new File(project.getBasedir(), mavenGlobalSettingsFile);
            }
            if (sf.exists()) {
                request.setGlobalSettingsFile(sf);
            } else {
                log.warn("Settings file not found, skipping: " + sf.getAbsolutePath());
            }
        }

        if (StringUtils.hasText(mavenProfile)) {
            request.setProfiles(List.of(mavenProfile));
        }

        Properties props = new Properties();
        props.setProperty("maven.test.skip", "true");
        request.setProperties(props);

        StringBuilder outputCapture = new StringBuilder();
        request.setOutputHandler(line -> {
            log.debug(line);
            outputCapture.append(line).append('\n');
        });

        log.info("Invoking Maven goal '%s' for %s:%s:%s".formatted(goal, groupId, artifactId, version));
        try {
            Invoker invoker = createInvoker();
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                String output = outputCapture.toString();
                if (isAlreadyExistsError(output)) {
                    log.warn("Artifact %s:%s:%s already exists in the repository — skipping."
                            .formatted(groupId, artifactId, version));
                } else {
                    log.error(output);
                    throw new MojoExecutionException(
                            "Maven command failed (exit %d) for %s:%s:%s"
                                    .formatted(result.getExitCode(), groupId, artifactId, version));
                }
            }
        } catch (MavenInvocationException e) {
            throw new MojoExecutionException("Failed to invoke Maven for " + artifactId, e);
        }
    }

    protected Invoker createInvoker() {
        return new DefaultInvoker();
    }

    private static String toKebabCase(String input) {
        return input.replaceAll("([A-Z])", "-$1").replaceFirst("^-", "").toLowerCase();
    }

    private boolean isAlreadyExistsError(String output) {
        String lower = output.toLowerCase();
        return ALREADY_EXISTS_PATTERNS.stream()
                .anyMatch(pattern -> lower.contains(pattern.toLowerCase()));
    }

    private File resolveMavenExecutable() {
        if (mavenExecutable != null && !mavenExecutable.isBlank() && Files.exists(Path.of(mavenExecutable))) {
            return Path.of(mavenExecutable).toFile();
        }
        String home = System.getProperty("maven.home");
        if (home != null) {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            Path mvnPath = Path.of(home, "bin", isWindows ? "mvn.cmd" : "mvn");
            if (Files.exists(mvnPath)) return mvnPath.toFile();
        }
        return null;
    }

    private String getJavaRelease() {
        String release = project.getProperties().getProperty("maven.compiler.release");
        if (StringUtils.hasText(release)) {
            return release;
        }
        String source = project.getProperties().getProperty("maven.compiler.source");
        if (StringUtils.hasText(source)) {
            return source;
        }
        return String.valueOf(Runtime.version().feature());
    }

    @SuppressWarnings("java:S5443")
    private File createTempDir(String indexTypeName) throws MojoExecutionException {
        try {
            return Files.createTempDirectory("index-type-deploy-" + indexTypeName.toLowerCase()).toFile();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create temp dir", e);
        }
    }

    private void deleteTempDir(File dir) {
        try (var paths = Files.walk(dir.toPath())) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException _) {
            // do ignore
        }
    }
}
