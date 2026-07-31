# Agent Guide — jeap-opensearch-index-writer-service

## What this project is

A Spring Boot **service template** for event-driven indexing into OpenSearch. Consuming services depend on this template and add their own `IndexType` definitions, message configurations, and (optionally) feature flags. The service listens to Kafka events, fetches SearchItems from a REST provider, and writes them to OpenSearch.

## Related projects (separate Maven repos)

| Project | Role |
|---------|------|
| `jeap-opensearch-index-type` | Defines the `IndexType<T>` interface and domain types (`SearchItem`, `Origin`, …). Consuming services implement `IndexType`. |
| `jeap-opensearch-client-starter` | Spring Boot starter for reading from OpenSearch. |
| `jeap-opensearch-searchitem-api` | OpenAPI spec / client for the SearchItem provider API. |

**Important:** When making changes to `jeap-opensearch-index-type`, run `mvn install -DskipTests` in that project before compiling this one — it is a local dependency not published to any remote repository during development.

## Module structure

```
jeap-opensearch-index-writer-domain            ← Pure domain: interfaces, domain model, no framework
jeap-opensearch-index-writer-adapter-opensearch ← OpenSearch adapter: IndexTemplateManager, PhysicalIndexManager, IndexMappingManager
jeap-opensearch-index-writer-adapter-kafka      ← Kafka consumer adapter
jeap-opensearch-index-writer-adapter-remote-data ← REST client for the SearchItem provider
jeap-opensearch-index-writer-index-config-repository ← Reads message/operation config from JSON
jeap-opensearch-index-writer-index-type-repository   ← Collects IndexType Spring beans
jeap-opensearch-index-writer-web               ← Spring Boot app wiring, IT tests
jeap-opensearch-index-writer-service-instance  ← Thin BOM for service instances to depend on
```

## Build and test

```bash
mvn verify                          # build + unit tests + integration tests
mvn test                            # unit tests only
mvn test -pl jeap-opensearch-index-writer-adapter-opensearch   # single module (runs IT too)
```

Integration tests use **Testcontainers** (Docker must be running). The web IT test (`IndexWriterIT`) also spins up Kafka via `KafkaIntegrationTestBase`.

## Key architectural decisions

### Index template management (service-owned)
The service creates and manages OpenSearch index templates itself at startup (via `IndexMappingUpdater` → `IndexWriter.ensureIndexReady`). Templates are NOT pre-created by IaC.

- Template name = write alias without `_write` (e.g. `jme_decree_document_v1_write` → `jme_decree_document_v1`)
- Template settings (shards, replicas, refresh interval) are configured in `application.yml` under `jeap.opensearch.indexwriter.index-templates.<templateName>` or the special `default` key as fallback. Missing configuration → startup fails with a clear error.
- The template is unconditionally PUT on every startup (mapping + settings). Settings changes only take effect for new partitions created by ISM rollover; existing partitions are not affected. The mapping is applied to existing partitions immediately.

### Write alias not in template (ISM rollover constraint)
The write alias must NOT be in the index template. ISM rollover creates the new physical index from the template — if the write alias were in the template, it would apply to both old and new indices simultaneously, causing OpenSearch to reject the rollover with "Rollover alias can point to multiple indices, found duplicated alias". The write alias is set explicitly only when the initial physical index is created.

### IndexType interface
`IndexType<T>` (from `jeap-opensearch-index-type`) is implemented by consuming services as a Spring bean. The writer service discovers all `IndexType` beans via `IndexTypeRepository` and calls `ensureIndexReady` for each on startup.

`IndexTemplateSettings` (shards/replicas/refresh interval) is NOT part of `IndexType` — it is infrastructure configuration in `application.yml`.

### Template settings flow
```
application.yml
  └─ IndexWriterProperties (@ConfigurationProperties)
       └─ IndexTemplateSettingsProvider (bean)
            └─ IndexMappingUpdater (@PostConstruct)
                 └─ IndexWriter.ensureIndexReady(alias, readAlias, version, mapping, settings)
                      └─ IndexTemplateManager / PhysicalIndexManager / IndexMappingManager
```

## Configuration required by consuming services

```yaml
jeap:
  opensearch:
    indexwriter:
      connection:
        url: https://my-opensearch:9200   # scheme optional, defaults to https; validated at startup
      index-templates:
        default:
          number-of-shards: 1
          number-of-replicas: 1
          refresh-interval: "1s"
        my_index_v1:            # override per template (key = template name without _write)
          number-of-shards: 3
          number-of-replicas: 2
          refresh-interval: "5s"
```

## Coding conventions

- Hexagonal architecture: domain module has zero Spring Boot autoconfiguration dependencies. Adapters wire everything.
- Lombok is used throughout (`@Data`, `@RequiredArgsConstructor`, `@Slf4j`, …).
- No comments unless the WHY is non-obvious. No docstrings.
- Custom exceptions extend `IndexWriterException`; OpenSearch-adapter exceptions use `OpenSearchIndexWriterException` factory methods.
- README.md must be updated whenever behaviour or configuration changes.

# Versioning

- Semantic Versioning; all changes documented in [CHANGELOG.md](./CHANGELOG.md) (Keep a Changelog format).
- `setPomVersions.sh` updates the version across all module POMs.
- When working on a feature branch, increase the version to `x.y.z-SNAPSHOT` in the POMs.
- Always keep the -SNAPSHOT postfix in the POMs, CI will remove it when releasing a version. Do not use the SNAPSHOT postfix in other places (CHANGELOG, publiccode.yml etc)
- Keep changelog entries concise and to the point, follow existing patterns
- Keep commit messages short, use the JIRA ID from the branch name as a prefix, do not use conventional commits (for example: "JEAP-1234 Added feature X")
- When bumping the version, also update the changelog, and update version/date in `publiccode.yml`.
- When the version on a feature branch has not yet been bumped compared to master, ask the user if a major, minor or patch version bump should be performed, and update the version accordingly.
