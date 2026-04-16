# jeap-opensearch-index-type-registry-maven-plugin

Maven plugin for managing an **OpenSearch Index Type Registry** — a Git repository that documents all OpenSearch index types used within a microservice landscape.

The plugin validates the registry on every build, enforces immutability and backward-compatibility rules for existing mappings, and generates per-index-type Maven artifacts containing typed Java records, an `IndexType` singleton, and mapping files.

---

## Table of Contents

- [Overview](#overview)
- [Registry Structure](#registry-structure)
- [Descriptor File](#descriptor-file)
- [Mapping File](#mapping-file)
- [Validation Rules](#validation-rules)
- [Generated Artifacts](#generated-artifacts)
- [Setting Up a Registry Project](#setting-up-a-registry-project)
- [Plugin Goals](#plugin-goals)
  - [registry](#goal-registry)
  - [deploy-index-type-artifacts](#goal-deploy-index-type-artifacts)
- [Local Development](#local-development)
- [Versioning Model](#versioning-model)

---

## Overview

Every OpenSearch index type used in the system must be registered in the Index Type Registry. The registry is a Git repository with the following responsibilities:

- **Single source of truth** for index type names, roles, and mapping schemas
- **Backward-compatibility enforcement** — existing mapping versions are immutable once merged to the trunk branch
- **Library generation** — each index type is published as an individual Maven artifact containing a typed Java record, an `IndexType<T>` singleton, and the raw mapping files

The plugin mirrors the pattern of `jeap-messaging-registry-maven-plugin` applied to OpenSearch mappings instead of Avro/Kafka message types.

---

## Registry Structure

```
<registry-root>/
└── index-types/
    └── <system>/                           # Lowercase system name, e.g. "jme"
        └── <indextypename>/                # Lowercase, e.g. "jmedecreedocument"
            ├── <IndexTypeName>.json        # Descriptor (PascalCase filename)
            ├── <IndexTypeName>_mapping_v1_0.json
            ├── <IndexTypeName>_mapping_v1_1.json
            └── <IndexTypeName>_mapping_v2_0.json
```

### Naming Conventions

| Element              | Convention                                         | Example                               |
|----------------------|----------------------------------------------------|---------------------------------------|
| System directory     | lowercase                                          | `jme`                                 |
| Index type directory | lowercase, no separators                           | `jmedecreedocument`                   |
| Descriptor file      | PascalCase, matches directory name lowercased      | `JmeDecreeDocument.json`              |
| Mapping file         | `<DescriptorName>_mapping_v<major>_<minor>.json`   | `JmeDecreeDocument_mapping_v1_0.json` |

---

## Descriptor File

Each index type has exactly one descriptor JSON file. The filename must be PascalCase and its lowercase form must equal the containing directory name.

```json
{
  "system": "JME",
  "originType": "JmeDecreeDocument",
  "description": "Indexes decree documents from the JME Process Archive Service.",
  "documentationUrl": "https://confluence.example.com/display/JME/DecreeDocument",
  "roles": [
    "jme_read"
  ],
  "mappingVersions": [
    { "major": 1, "minor": 0, "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json" },
    { "major": 1, "minor": 1, "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json" }
  ]
}
```

| Field              | Required | Description                                                        |
|--------------------|----------|--------------------------------------------------------------------|
| `system`           | yes      | System identifier (free text, e.g. `"JME"`)                        |
| `originType`       | yes      | Name of the originating archive/domain type                        |
| `description`      | yes      | Human-readable description of what is indexed                      |
| `roles`            | yes (≥1) | Security roles required to read this index                         |
| `mappingVersions`  | yes (≥1) | List of all mapping versions; each entry references a mapping file |
| `documentationUrl` | no       | Link to further documentation                                      |

---

## Mapping File

Each mapping file is a standard OpenSearch mapping JSON. Three top-level sections are required:

```json
{
  "mappings": {
    "dynamic": false,
    "properties": {
      "search_item": {
        "type": "object",
        "properties": {
          "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
          "minor_version": { "type": "integer" }
        }
      },
      "origin": {
        "type": "object",
        "properties": {
          "id":        { "type": "keyword" },
          "version":   { "type": "keyword" },
          "bp_id":     { "type": "keyword" },
          "created":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
          "modified":  { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
          "reference": { "type": "object", "enabled": false }
        }
      },
      "data": {
        "type": "object",
        "properties": {
          "document_id": { "type": "keyword" },
          "decree_reference": {
            "type": "object",
            "properties": {
              "type": { "type": "keyword" },
              "id":   { "type": "keyword" }
            }
          },
          "created_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" }
        }
      }
    }
  }
}
```

| Section | Written by | Purpose |
|---|---|---|
| `search_item` | jEAP IndexWriter Service | Indexing metadata (timestamps, minor version) |
| `origin` | jEAP IndexWriter Service | Reference back to the source business object |
| `data` | Application | Business fields — freely defined, this section is mapped to Java |

`dynamic: false` is required. The `search_item` and `origin` sections must conform to the bundled JSON schema; the `data` section is application-defined.

### OpenSearch → Java Type Mapping

| OpenSearch type                                   | Java type           |
|---------------------------------------------------|---------------------|
| `keyword`, `text`, `wildcard`, `constant_keyword` | `String`            |
| `integer`, `short`, `byte`                        | `Integer`           |
| `long`                                            | `Long`              |
| `float`, `half_float`                             | `Float`             |
| `double`, `scaled_float`                          | `Double`            |
| `boolean`                                         | `Boolean`           |
| `date`                                            | `java.time.Instant` |
| `binary`                                          | `String`            |
| `object` / `nested` (no sub-properties)           | `JsonNode`          |

Nested `object` fields that have sub-`properties` become inner records (e.g. `decree_reference` → `public record DecreeReference(...) {}`). Fields whose JSON name differs from their Java name get a `@JsonProperty` annotation.

---

## Validation Rules

The `registry` goal enforces the following rules on every build:

### Naming & Structure
- System and index type directory names must be all-lowercase
- The system directory name must match the `system` field in the descriptor (case-insensitive)
- The index type directory name must equal the descriptor filename lowercased (without `.json`)
- The index type name must start with the system name in title case (e.g. system `JME` → index type must start with `Jme`)
- Only the descriptor JSON and versioned mapping files are allowed in an index type directory

### Schema Validation
- Every descriptor file is validated against `IndexTypeDescriptor.schema.json`
- Every mapping file is validated against `IndexTypeMappingDescriptor.schema.json`

### Immutability (compared to trunk branch)
- No system directory may be deleted
- No index type directory may be deleted
- No existing mapping file may be changed (detected by CRC32 checksum)

The plugin clones the configured `gitUrl` at the `trunkBranchName` branch and compares the current working tree against it.

### Minor Version Backward Compatibility
Within the same major version, each successive minor version may only **add** properties to the `data` section — never remove them. This is checked for every adjacent minor version pair (e.g. v1.0→v1.1, v1.1→v1.2).

To introduce a breaking change, increment the major version. A new major version generates a new Java record class (e.g. `JmeDecreeDocumentDataV2`) and a new `IndexType` singleton (`JmeDecreeDocumentIndexTypeV2`).

### Role Changes
The `roles` field may only be changed when at least one new mapping version (major or minor) is introduced at the same time. Changing roles without adding a new version fails the build.

---

## Generated Artifacts

### Per-type Maven JARs

Each (index type, major version) pair produces an individual Maven artifact. The groupId is formed by appending the lowercase system name to `groupIdPrefix`; the artifactId includes the major version suffix `-v<major>`:

```
<groupIdPrefix>.<system>:<kebab-case-name>-v<major>:<major>.<minor>
```

For example, with `groupIdPrefix=ch.admin.bit.jme.indextype` for a type `JmeDecreeDocument` in system `JME` at major 1, minor 2:

```
ch.admin.bit.jme.indextype.jme:jme-decree-document-v1:1.2
```

The `-v<major>` suffix in the artifact ID allows a service to declare dependencies on multiple major versions of the same index type simultaneously.

On feature branches the version becomes `<major>.<minor>-<sanitized-branch>-SNAPSHOT`.

The generated classes are placed in a sub-package derived from the base package, system name, and the type name with the system prefix removed:

```
<basePackage>.<system-lowercase>.<typename-without-system-prefix-lowercase>
```

For `JmeDecreeDocument` with `basePackage=ch.admin.bit.jme.opensearch.index` this gives `ch.admin.bit.jme.opensearch.index.jme.decreedocument`.

Each JAR contains:

| Path                                                                                    | Description                                              |
|-----------------------------------------------------------------------------------------|----------------------------------------------------------|
| `ch/admin/.../jme/decreedocument/<IndexType>DataV<major>.class`                         | Immutable data record for the `data` section             |
| `ch/admin/.../jme/decreedocument/<IndexType>IndexTypeV<major>.class`                    | `IndexType<T>` singleton with metadata and mapping ref   |
| `opensearch/<IndexType>_mapping_v<major>_<minor>.json`                                  | Mapping files for all minors of this major version       |
| `META-INF/index-types.json`                                                             | Runtime metadata (system, roles, mapping versions, FQNs) |
| `META-INF/services/ch.admin.bit.jeap.opensearch.indextype.IndexType`                    | ServiceLoader registration                               |

### Generated Java Code Example

For `JmeDecreeDocument` v1 with the `data` section above, the plugin generates two files in package `ch.admin.bit.jme.opensearch.index.jme.decreedocument`.

**Data record** (`JmeDecreeDocumentDataV1.java`):
```java
package ch.admin.bit.jme.opensearch.index.jme.decreedocument;

public record JmeDecreeDocumentDataV1(
    @JsonProperty("document_id") String documentId,
    DecreeReference decreeReference,
    @JsonProperty("created_at") Instant createdAt
) {
    public record DecreeReference(String type, String id) {}
}
```

When a later minor version adds a field (e.g. v1.1 adds `status`), the record for the latest minor also gets a backward-compatible constructor that delegates to the canonical constructor with `null` for the new field, so existing callers that construct the record with older positional arguments continue to compile.

**IndexType singleton** (`JmeDecreeDocumentIndexTypeV1.java`):
```java
package ch.admin.bit.jme.opensearch.index.jme.decreedocument;

public final class JmeDecreeDocumentIndexTypeV1 implements IndexType<JmeDecreeDocumentDataV1> {
    public static final JmeDecreeDocumentIndexTypeV1 INSTANCE = new JmeDecreeDocumentIndexTypeV1();
    public JmeDecreeDocumentIndexTypeV1() {}

    @Override public String system()           { return "JME"; }
    @Override public String originType()       { return "JmeDecreeDocument"; }
    @Override public int    majorVersion()     { return 1; }
    @Override public int    minorVersion()     { return 1; }
    @Override public String description()      { return "..."; }
    @Override public String documentationUrl() { return "..."; }
    @Override public List<String> roles()      { return List.of("jme_read"); }
    @Override public Class<JmeDecreeDocumentDataV1> dataClass() { return JmeDecreeDocumentDataV1.class; }
    @Override public Supplier<InputStream> mappingDefinition() {
        return () -> getClass().getResourceAsStream("/opensearch/JmeDecreeDocument_mapping_v1_1.json");
    }
}
```

### ServiceLoader Discovery

All `IndexType` implementations are registered as `ServiceLoader` providers. To load all index types available on the classpath at runtime:

```java
List<IndexType<?>> allTypes = IndexType.loadAll();
// or with a specific ClassLoader:
List<IndexType<?>> allTypes = IndexType.loadAll(myClassLoader);
```

### META-INF/index-types.json

```json
{
  "indexTypes": [
    {
      "indexTypeName": "JmeDecreeDocument",
      "system": "JME",
      "roles": ["jme_read"],
      "mappingVersions": [
        {
          "major": 1, "minor": 0,
          "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json",
          "beanClassName": "ch.admin.bit.jme.opensearch.index.jme.decreedocument.JmeDecreeDocumentIndexTypeV1"
        },
        {
          "major": 1, "minor": 1,
          "mappingDefinition": "JmeDecreeDocument_mapping_v1_1.json",
          "beanClassName": "ch.admin.bit.jme.opensearch.index.jme.decreedocument.JmeDecreeDocumentIndexTypeV1"
        }
      ]
    }
  ]
}
```

---

## Setting Up a Registry Project

A registry project has `packaging: pom`. It does not produce a JAR itself — only the per-type artifacts are deployed.

```xml
<project>
    <groupId>ch.admin.bit.jme</groupId>
    <artifactId>jme-index-type-registry</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <!-- Prevents Maven from deploying the registry POM as an artifact -->
        <maven.deploy.skip>true</maven.deploy.skip>

        <!-- Override with -Dindextype.git.url= to skip master branch comparison locally -->
        <indextype.git.url>https://github.com/your-org/your-index-type-registry.git</indextype.git.url>

        <jeap-opensearch-index-type-registry.version>...</jeap-opensearch-index-type-registry.version>
    </properties>

    <build>
        <plugins>
            <plugin>
                <groupId>ch.admin.bit.jeap</groupId>
                <artifactId>jeap-opensearch-index-type-registry-maven-plugin</artifactId>
                <version>${jeap-opensearch-index-type-registry.version}</version>
                <configuration>
                    <descriptorDirectory>${basedir}/index-types</descriptorDirectory>
                    <basePackage>ch.admin.bit.jme.opensearch.index</basePackage>
                    <gitUrl>${indextype.git.url}</gitUrl>
                    <gitTokenEnvVariableName>GITHUB_TOKEN</gitTokenEnvVariableName>
                    <trunkBranchName>master</trunkBranchName>
                    <indexTypeVersion>${jeap-opensearch-index-type-registry.version}</indexTypeVersion>
                </configuration>
                <executions>
                    <execution>
                        <id>validate-registry</id>
                        <goals><goal>registry</goal></goals>
                    </execution>
                    <execution>
                        <id>deploy-index-type-artifacts</id>
                        <goals><goal>deploy-index-type-artifacts</goal></goals>
                        <configuration>
                            <groupIdPrefix>ch.admin.bit.jme.indextype</groupIdPrefix>
                            <mavenDeployGoal>deploy</mavenDeployGoal>
                            <mavenGlobalSettingsFile>${basedir}/.github/actions/settings.xml</mavenGlobalSettingsFile>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

To deploy to two repositories (e.g. Nexus and CodeArtifact), add a second `deploy-index-type-artifacts` execution with a different `mavenGlobalSettingsFile`.

---

## Plugin Goals

### Goal: `registry`

**Default phase:** `verify`

Validates the registry and generates Java sources.

| Parameter                  | Default                                                            | Description                                                                                                               |
|----------------------------|--------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `descriptorDirectory`      | `${basedir}/index-types`                                           | Root directory of the index type descriptors                                                                              |
| `basePackage`              | *(required)*                                                       | Java package for generated classes, e.g. `ch.admin.bit.jme.opensearch.index`                                              |
| `gitUrl`                   | —                                                                  | Git URL of the registry repository, used to clone the trunk branch for comparison. Leave blank to skip master comparison. |
| `gitTokenEnvVariableName`  | `INDEX_TYPE_REPO_GIT_TOKEN`                                        | Name of the environment variable holding the Git token. Falls back to unauthenticated system git if not set.              |
| `trunkBranchName`          | `master`                                                           | Branch to compare against                                                                                                 |
| `outputDirectory`          | `${project.build.directory}/generated-sources/index-type-registry` | Where to write generated Java sources                                                                                     |
| `outputResourcesDirectory` | `${project.build.outputDirectory}`                                 | Where to write `META-INF/` resources and `opensearch/` mapping files                                                      |
| `skipGeneration`           | `false`                                                            | If `true`, validation still runs but no Java sources are generated                                                        |

### Goal: `deploy-index-type-artifacts`

**Default phase:** `deploy`

Packages and deploys one Maven artifact per (index type, major version) pair.

| Parameter                  | Default                                                            | Description                                                                                                                                                                                                                                                                                                   |
|----------------------------|--------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `groupIdPrefix`            | *(required)*                                                       | GroupId prefix for generated artifacts, e.g. `ch.admin.bit.jme.indextype`. The system name is appended automatically: `ch.admin.bit.jme.indextype.jme`                                                                                                                                                        |
| `indexTypeVersion`         | *(required)*                                                       | Version of `jeap-opensearch-index-type` library declared as a dependency in each generated artifact's pom                                                                                                                                                                                                     |
| `basePackage`              | *(required)*                                                       | Must match the `registry` goal configuration                                                                                                                                                                                                                                                                  |
| `mavenDeployGoal`          | `deploy`                                                           | `deploy` to push to a remote repository, `install` to install to the local Maven repository                                                                                                                                                                                                                   |
| `mavenGlobalSettingsFile`  | —                                                                  | Path to a Maven `settings.xml` passed via `--settings`. Relative paths are resolved against the project base directory. Silently skipped if the file does not exist.                                                                                                                                          |
| `deployAllIndexTypes`      | `false`                                                            | When `false`, only index types with new or added mapping files compared to the trunk branch are deployed. Requires `gitUrl`; ignored (all deployed) if `gitUrl` is blank.                                                                                                                                     |
| `gitUrl`                   | —                                                                  | Git URL of the registry, used to clone the trunk branch for change detection. Leave blank to deploy all index types unconditionally.                                                                                                                                                                          |
| `gitTokenEnvVariableName`  | `INDEX_TYPE_REPO_GIT_TOKEN`                                        | Name of the environment variable holding the Git token for cloning. Falls back to unauthenticated system git if not set.                                                                                                                                                                                      |
| `trunkBranchName`          | `master`                                                           | Branch to compare against for change detection and profile selection                                                                                                                                                                                                                                          |
| `currentBranch`            | `${git.branch}`                                                    | Injected by `git-commit-id-maven-plugin`. When equal to `trunkBranchName`, `trunkMavenProfile` is activated and artifact version is `major.minor`; otherwise version is `major.minor-<branch>-SNAPSHOT`.                                                                                                      |
| `trunkMavenProfile`        | —                                                                  | Maven profile activated only when building on the trunk branch, typically configuring releases repository credentials.                                                                                                                                                                                        |
| `pomTemplateFile`          | —                                                                  | Path to a custom POM template used when generating each artifact's `pom.xml`. If not set, the plugin looks for `indextype.template.pom.xml` in `descriptorDirectory`; if that file is absent too, the built-in default template is used. Useful for adding OSS-publication metadata (license, SCM URL, etc.). |
| `skip`                     | `false`                                                            | Skip this goal entirely                                                                                                                                                                                                                                                                                       |
| `descriptorDirectory`      | `${basedir}/index-types`                                           | Must match the `registry` goal configuration                                                                                                                                                                                                                                                                  |
| `outputDirectory`          | `${project.build.directory}/generated-sources/index-type-registry` | Must match the `registry` goal configuration                                                                                                                                                                                                                                                                  |
| `outputResourcesDirectory` | `${project.build.outputDirectory}`                                 | Must match the `registry` goal configuration                                                                                                                                                                                                                                                                  |

The goal is **idempotent**: if an artifact already exists in the repository (HTTP 409 Conflict), it logs a warning and continues with the remaining index types rather than failing the build.

---

## Local Development

### Validate the registry locally (without GitHub access)

```bash
mvn verify -Dindextype.git.url=
```

This skips the master branch comparison and only validates the local files.

### Install per-type artifacts to the local Maven repository

```bash
mvn deploy -Dindextype.git.url= -DmavenDeployGoal=install
```

### Run validation only (skip artifact generation)

```bash
mvn verify -Dindextype.git.url= -DskipGeneration=true
```

---

## Versioning Model

| Change                             | Action                        | Result                                                                                                |
|------------------------------------|-------------------------------|-------------------------------------------------------------------------------------------------------|
| Add a new field to `data`          | Increment **minor** version   | New mapping file `_mapping_v1_1.json`; data record updated; compat constructor added for v1.0 callers |
| Remove or rename a field in `data` | Increment **major** version   | New mapping file `_mapping_v2_0.json`; new record `DataV2` and singleton `IndexTypeV2` generated      |
| Add a new index type               | New descriptor + mapping file | New per-type artifact                                                                                 |
| Delete an index type               | **Not allowed**               | Build fails — types and mapping files on master are immutable                                         |
| Modify an existing mapping file    | **Not allowed**               | Build fails — detected by CRC32 comparison against master                                             |

### Artifact Versioning

Artifact IDs follow `<kebab-case-name>-v<major>` (e.g. `jme-decree-document-v1`). The `-v<major>` suffix allows a service to depend on multiple major versions of the same index type simultaneously.

Artifact versions follow `<major>.<minor>` (e.g. `1.2`), not the registry project version. On the trunk branch the version is a release version; on feature branches it becomes `<major>.<minor>-<branch>-SNAPSHOT` so feature-branch artifacts can be tested without polluting the release repository.
