# Architecture

jEAP OpenSearch Index Writer is a Spring Boot service template for event-driven indexing of search
items into OpenSearch. A service instance is created by depending on the template and adding
index type and message configuration specific to the owning domain.

```mermaid
flowchart LR
    subgraph DomainService["Domain Service"]
        DS["Domain Service"]
        Event{{Domain Event}}
    end

    subgraph ServiceInstance["Service Instance\n(jeap-opensearch-index-writer-service-instance)"]
        IndexWriter["OpenSearch Index Writer"]
        MessagesJson[/"messages.json"/]
    end

    subgraph MavenRepo["Maven Repository"]
        IndexType["IndexType"]
    end

    OpenSearch[["OpenSearch Cluster"]]

    DS -- "publishes" --> Event
    Event -- "triggers" --> IndexWriter
    IndexWriter -- "GET SearchItem" --> DS
    IndexWriter -- "create/update templates & mappings\nUPSERT / DELETE documents" --> OpenSearch

    DS -. "depends on" .-> IndexType
    IndexWriter -. "depends on" .-> IndexType

    classDef event fill:#8888d9,stroke:#888,color:#333
    classDef service fill:#d4edda,stroke:#5a9e6f,color:#1a3a2a
    classDef core fill:#d9d9d9,stroke:#666,color:#333,font-weight:bold
    classDef artifactlight fill:#dceefb,stroke:#6699cc,color:#1a3a5c
    classDef target fill:#ffffff,stroke:#666,color:#333
    classDef repo fill:#ececec,stroke:#999,color:#333

    class Event event
    class DS service
    class IndexWriter core
    class OpenSearch target
    class IndexType repo
    class MessagesJson artifactlight
```

## Modules and responsibilities

The template follows hexagonal architecture: the domain module contains all business logic and
defines ports (interfaces); adapters implement those ports against concrete infrastructure.

| Module                    | Key types                                                                                      | Responsibility                                                     |
|---------------------------|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `domain`                  | `MessageIndexingService`, `IndexWriter`, `ReferenceProvider`, `IndexingCondition`              | Core indexing logic; no framework dependency                       |
| `adapter-opensearch`      | `OpenSearchIndexWriter`, `IndexTemplateManager`, `PhysicalIndexManager`, `IndexMappingManager` | Creates/updates index templates and writes documents to OpenSearch |
| `adapter-kafka`           | `KafkaIndexWriterMessageListener`, `KafkaIndexWriterConsumerFactory`                           | Consumes Kafka events and forwards them to the domain              |
| `adapter-remote-data`     | `HttpSearchItemProvider`                                                                       | Fetches `SearchItem` data from the owning domain service via REST  |
| `index-config-repository` | `JsonMessageConfigurationRepository`                                                           | Loads and parses `/opensearch/messages.json` at startup            |
| `index-type-repository`   | `ServiceLoaderIndexTypeRepository`                                                             | Discovers registered `IndexType` Spring beans                      |
| `web`                     | `OpenSearchIndexWriterApplication`                                                             | Spring Boot wiring, auto-configuration                             |
| `service-instance`        | BOM                                                                                            | Thin dependency entry point for service instances                  |

## Event flow

An incoming Kafka event travels through the adapters to the domain and ends up as a document write
in OpenSearch:

```mermaid
flowchart LR
    K[(Kafka topic)] -->|event message| Listener[KafkaIndexWriterMessageListener]
    Listener --> MIS[MessageIndexingService]
    MIS -->|extractReference| RP[ReferenceProvider]
    RP -->|OriginReference| MIS
    MIS -->|GET SearchItem| Provider[HttpSearchItemProvider]
    Provider -->|SearchItem| MIS
    MIS -->|validate + enrich| IW[OpenSearchIndexWriter]
    IW -->|UPSERT / DELETE| OS[(OpenSearch)]
```

Step by step:

1. **Consume.** `KafkaIndexWriterMessageListener` receives a message from the configured Kafka topic
   and forwards it to the `MessageIndexingService`.
2. **Extract references.** The `ReferenceProvider` bean configured for the operation extracts one or
   more `OriginReference` objects from the message. Each reference is processed independently.
3. **Fetch SearchItem.** For `UPSERT` operations, `HttpSearchItemProvider` calls the owning domain
   service's SearchItem Provider endpoint and retrieves the current search representation.
4. **Validate and enrich.** The service validates the `SearchItem` fields against the `IndexType`
   mapping and enriches the document with metadata (`upserted_at`, `major_version`, `minor_version`).
5. **Write.** `OpenSearchIndexWriter` sends the document to OpenSearch via the `IndexWriteAlias`
   using `UPSERT` (PUT) or `DELETE`.
6. **Error handling.** If any step fails, the triggering event is forwarded to the jEAP error
   handling service.

## Startup flow

On startup, `IndexMappingUpdater` iterates over every registered `IndexType` and calls
`ensureIndexReady`:

```mermaid
flowchart TD
    Start([Startup]) --> Template[PUT index template]
    Template --> AliasExists{Write alias exists?}
    AliasExists -->|No| CreateIndex[Create physical index\nwith write alias]
    AliasExists -->|Yes| CheckVersion{Mapping version\nmatches?}
    CreateIndex --> CheckVersion
    CheckVersion -->|Yes| Done([Ready])
    CheckVersion -->|No| UpdateMapping[PUT mapping to\ncurrent write index]
    UpdateMapping --> Done
```

See [Startup behaviour](startup-behaviour.md) for details.

## Key design decisions

**Service-owned templates.** Index templates are created and managed by the index writer service at
startup, not by IaC. This keeps the template in sync with the `IndexType` mapping and removes the
need for separate IaC for each index.

**Write alias not in template.** The write alias is set explicitly only when the initial physical
index is created. Placing it in the template would cause OpenSearch to reject ISM rollovers with a
duplicate-alias error.

**Declarative message configuration.** All message-to-operation mappings are declared in a JSON
file rather than in code. This allows the same template code to power many different service
instances without any business logic changes.

**Hexagonal architecture.** The domain module carries no framework dependency. All Spring Boot,
Kafka, and OpenSearch specifics live in adapters, making the core logic independently testable.

## Related

- [Getting started](getting-started.md)
- [Startup behaviour](startup-behaviour.md)
- [Message configuration](message-configuration.md)
- [Index types](index-types.md)
- [Write operations](write-operations.md)
- [jeap-opensearch-index-writer-service](../README.md)
