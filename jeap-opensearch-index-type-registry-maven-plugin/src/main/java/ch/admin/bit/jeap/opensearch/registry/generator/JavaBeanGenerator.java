package ch.admin.bit.jeap.opensearch.registry.generator;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class JavaBeanGenerator {
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final File outputDirectory;
    private final String basePackage;
    private final Log log;

    public JavaBeanGenerator(File outputDirectory, String basePackage, Log log) {
        this.outputDirectory = outputDirectory;
        this.basePackage = basePackage;
        this.log = log;
    }

    /**
     * Generates the data record and IndexType singleton for one (indexType, majorVersion).
     *
     * @return FQN of the generated IndexType class
     */
    public static String packageFor(String basePackage, String system, String indexTypeName) {
        String lowerName = indexTypeName.toLowerCase();
        String lowerSystem = system.toLowerCase();
        String nameWithoutSystem = lowerName.startsWith(lowerSystem)
                ? lowerName.substring(lowerSystem.length()) : lowerName;
        return basePackage + "." + lowerSystem + "." + nameWithoutSystem;
    }

    public String generate(String indexTypeName, String system, String description,
                           String documentationUrl, List<String> roles,
                           int majorVersion, int latestMinorVersion,
                           File latestMappingFile, List<File> allMinorMappingsForMajor)
            throws MojoExecutionException {

        String dataClassName = indexTypeName + "DataV" + majorVersion;
        String indexTypeClassName = indexTypeName + "IndexTypeV" + majorVersion;
        String pkg = packageFor(basePackage, system, indexTypeName);

        JsonNode latestMapping;
        try {
            latestMapping = JSON_MAPPER.readTree(latestMappingFile);
        } catch (JacksonIOException e) {
            throw new MojoExecutionException("Cannot read mapping file: " + latestMappingFile, e);
        }

        JsonNode dataProperties = latestMapping.path("mappings").path("properties").path("data").path("properties");
        SequencedMap<String, FieldDef> latestFields = extractFields(dataProperties, "");

        List<SequencedMap<String, FieldDef>> olderFieldSets = collectOlderFieldSets(allMinorMappingsForMajor, latestFields);

        File packageDir = getPackageDir(pkg);
        writeFile(new File(packageDir, dataClassName + ".java"),
                buildDataRecordSource(pkg, dataClassName, latestFields, olderFieldSets));
        writeFile(new File(packageDir, indexTypeClassName + ".java"),
                buildIndexTypeSource(pkg, indexTypeClassName, dataClassName, system, indexTypeName,
                        description, documentationUrl, roles, majorVersion, latestMinorVersion,
                        indexTypeName + "_mapping_v" + majorVersion + "_" + latestMinorVersion + ".json"));

        String fqn = pkg + "." + indexTypeClassName;
        log.info("Generated: " + fqn);
        return fqn;
    }

    // -------------------------------------------------------------------------
    // Field extraction
    // -------------------------------------------------------------------------

    private SequencedMap<String, FieldDef> extractFields(JsonNode propertiesNode, String pathPrefix) {
        SequencedMap<String, FieldDef> fields = new LinkedHashMap<>();
        if (propertiesNode == null || propertiesNode.isMissingNode() || !propertiesNode.isObject()) {
            return fields;
        }
        for (Map.Entry<String, JsonNode> entry : propertiesNode.properties()) {
            String jsonName = entry.getKey();
            String javaName = OpenSearchTypeMapper.toCamelCase(jsonName);
            String osType = entry.getValue().path("type").asString("object");

            if ("object".equals(osType) || "nested".equals(osType)) {
                JsonNode nestedProps = entry.getValue().path("properties");
                if (!nestedProps.isMissingNode()) {
                    SequencedMap<String, FieldDef> subFields = extractFields(nestedProps, javaName);
                    fields.put(jsonName, new FieldDef(jsonName, javaName, null, subFields));
                } else {
                    fields.put(jsonName, new FieldDef(jsonName, javaName, "JsonNode", null));
                }
            } else {
                String javaType = simplifyType(OpenSearchTypeMapper.toJavaType(osType));
                fields.put(jsonName, new FieldDef(jsonName, javaName, javaType, null));
            }
        }
        return fields;
    }

    private String simplifyType(String fullType) {
        return fullType.startsWith("java.time.") ? fullType.substring(fullType.lastIndexOf('.') + 1) : fullType;
    }

    // -------------------------------------------------------------------------
    // Compat constructors
    // -------------------------------------------------------------------------

    private List<SequencedMap<String, FieldDef>> collectOlderFieldSets(
            List<File> allMinorMappings, SequencedMap<String, FieldDef> latestFields)
            throws MojoExecutionException {

        List<SequencedMap<String, FieldDef>> result = new ArrayList<>();
        for (File f : allMinorMappings) {
            if (f.equals(allMinorMappings.getLast())) continue; // skip latest
            try {
                JsonNode mapping = JSON_MAPPER.readTree(f);
                JsonNode dataProps = mapping.path("mappings").path("properties").path("data").path("properties");
                SequencedMap<String, FieldDef> fields = extractFields(dataProps, "");
                if (!fields.keySet().equals(latestFields.keySet())) {
                    // Only add if not already represented
                    boolean duplicate = result.stream().anyMatch(r -> r.keySet().equals(fields.keySet()));
                    if (!duplicate) {
                        result.add(fields);
                    }
                }
            } catch (JacksonIOException e) {
                throw new MojoExecutionException("Cannot read mapping file for compat constructors: " + f, e);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Data record source
    // -------------------------------------------------------------------------

    private String buildDataRecordSource(String pkg, String className,
                                         SequencedMap<String, FieldDef> fields,
                                         List<SequencedMap<String, FieldDef>> olderFieldSets) {
        boolean needsInstant = fields.values().stream().anyMatch(f -> "Instant".equals(f.javaType()));
        boolean needsJsonNode = fields.values().stream().anyMatch(f -> "JsonNode".equals(f.javaType())
                || (f.nestedFields() == null && "JsonNode".equals(f.javaType())));
        boolean needsJsonProperty = fields.values().stream().anyMatch(FieldDef::needsJsonProperty);
        boolean hasNestedObjects = fields.values().stream().anyMatch(FieldDef::isNested);
        // nested records also need @JsonProperty for their parent field
        needsJsonProperty = needsJsonProperty || hasNestedObjects && fields.values().stream()
                .filter(FieldDef::isNested).anyMatch(FieldDef::needsJsonProperty);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        if (needsJsonProperty) sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        if (needsJsonNode) sb.append("import tools.jackson.databind.JsonNode;\n");
        if (needsInstant) sb.append("import java.time.Instant;\n");
        sb.append("\n");

        sb.append("public record ").append(className).append("(\n");
        List<FieldDef> fieldList = new ArrayList<>(fields.values());
        for (int i = 0; i < fieldList.size(); i++) {
            FieldDef f = fieldList.get(i);
            sb.append("    ");
            if (f.needsJsonProperty()) {
                sb.append("@JsonProperty(\"").append(f.jsonName()).append("\") ");
            }
            sb.append(f.typeName()).append(" ").append(f.javaName());
            if (i < fieldList.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(")");

        boolean hasBody = !olderFieldSets.isEmpty() || hasNestedObjects;
        if (!hasBody) {
            sb.append(" {}\n");
            return sb.toString();
        }

        sb.append(" {\n");

        // Nested record classes
        if (hasNestedObjects) {
            for (FieldDef f : fieldList) {
                if (f.isNested()) {
                    sb.append("\n    public record ").append(f.typeName()).append("(\n");
                    List<FieldDef> subList = new ArrayList<>(f.nestedFields().values());
                    for (int i = 0; i < subList.size(); i++) {
                        FieldDef sf = subList.get(i);
                        sb.append("        ");
                        if (sf.needsJsonProperty()) {
                            sb.append("@JsonProperty(\"").append(sf.jsonName()).append("\") ");
                        }
                        sb.append(sf.typeName()).append(" ").append(sf.javaName());
                        if (i < subList.size() - 1) {
                            sb.append(",");
                        }
                        sb.append("\n");
                    }
                    sb.append("    ) {}\n");
                }
            }
        }

        // Compat constructors
        for (SequencedMap<String, FieldDef> olderFields : olderFieldSets) {
            List<FieldDef> olderList = new ArrayList<>(olderFields.values());
            sb.append("\n    public ").append(className).append("(\n");
            for (int i = 0; i < olderList.size(); i++) {
                FieldDef f = olderList.get(i);
                sb.append("        ").append(f.typeName()).append(" ").append(f.javaName());
                if (i < olderList.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("    ) {\n        this(");
            for (int i = 0; i < fieldList.size(); i++) {
                FieldDef f = fieldList.get(i);
                boolean presentInOlder = olderFields.containsKey(f.jsonName());
                sb.append(presentInOlder ? f.javaName() : "null");
                if (i < fieldList.size() - 1) sb.append(", ");
            }
            sb.append(");\n    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // IndexType singleton source
    // -------------------------------------------------------------------------

    private String buildIndexTypeSource(String pkg, String className, String dataClassName,
                                        String system, String originType,
                                        String description, String documentationUrl,
                                        List<String> roles, int majorVersion, int minorVersion,
                                        String mappingResourceFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import ch.admin.bit.jeap.opensearch.indextype.IndexType;\n");
        sb.append("import java.io.InputStream;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.function.Supplier;\n\n");

        sb.append("public final class ").append(className)
                .append(" implements IndexType<").append(dataClassName).append("> {\n\n");
        sb.append("    public static final ").append(className).append(" INSTANCE = new ").append(className).append("();\n");
        sb.append("    public ").append(className).append("() {}\n\n");

        sb.append("    @Override public String system()           { return \"").append(escape(system)).append("\"; }\n");
        sb.append("    @Override public String originType()       { return \"").append(escape(originType)).append("\"; }\n");
        sb.append("    @Override public int    majorVersion()     { return ").append(majorVersion).append("; }\n");
        sb.append("    @Override public int    minorVersion()     { return ").append(minorVersion).append("; }\n");
        sb.append("    @Override public String description()      { return \"").append(escape(description)).append("\"; }\n");
        sb.append("    @Override public String documentationUrl() { return \"").append(escape(documentationUrl)).append("\"; }\n");

        sb.append("    @Override public List<String> roles()      { return List.of(");
        for (int i = 0; i < roles.size(); i++) {
            sb.append("\"").append(escape(roles.get(i))).append("\"");
            if (i < roles.size() - 1) sb.append(", ");
        }
        sb.append("); }\n");

        sb.append("    @Override public Class<").append(dataClassName).append("> dataClass() { return ")
                .append(dataClassName).append(".class; }\n\n");

        sb.append("    @Override public Supplier<InputStream> mappingDefinition() {\n");
        sb.append("        return () -> getClass().getResourceAsStream(\"/opensearch/")
                .append(mappingResourceFile).append("\");\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private File getPackageDir(String pkg) throws MojoExecutionException {
        File dir = new File(outputDirectory, pkg.replace('.', '/'));
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot create output directory: " + dir, e);
        }
        return dir;
    }

    private void writeFile(File file, String content) throws MojoExecutionException {
        try {
            Files.writeString(file.toPath(), content);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write file: " + file, e);
        }
    }

    record FieldDef(String jsonName, String javaName, String javaType, SequencedMap<String, FieldDef> nestedFields) {
        boolean isNested() {
            return nestedFields != null;
        }

        boolean needsJsonProperty() {
            return !jsonName.equals(javaName);
        }

        String typeName() {
            if (isNested()) {
                return OpenSearchTypeMapper.toClassName(javaName);
            }
            return javaType;
        }
    }
}
