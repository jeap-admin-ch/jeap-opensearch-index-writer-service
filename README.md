# jeap-opensearch-index-writer-service

Service template to provide event-driven indexing of search items into OpenSearch. A service instance can be created by depending on this template, then adding specific message and operation configuration.

## Key Features

- **Declarative Message Configuration:** Messages with operations map an event type and topic to an index type and index operation (`UPSERT` or `DELETE`)
- **Multiple Operations:** Any number of operations can be configured per service instance
- **Reference Provider:** Each operation references a provider that extracts the business object reference (OriginType, Id, optional Version) from the incoming event
- **SearchItem Provider:** Each operation declares the host of the SearchItem Provider API from which the search item is fetched
- **Conditional Execution:** operations can reference a condition; the operation is only executed if the event satisfies it
- **Feature Flags:** operations can be guarded by a feature flag; the operation is skipped when the flag is inactive
- **Schema Validation:** Before writing, the service verifies that the SearchItem returned by the provider is compatible with the target IndexType definition
- **Managed Metadata:** Before writing, the service enriches each `SearchItem` with `search_item.upserted_at` (timestamp of the index write) and `search_item.minor_version` (minor version of the `IndexType` mapping used at write time)
- **Index Alias Writes:** All writes use the `IndexWriteAlias` of the IndexTypeVersion, supporting platform-managed index rotation
- **Error Handling:** If an index operation fails, the triggering event is forwarded to the jEAP error handling service
- **Startup Mapping Validation:** On startup, the service compares the index mappings against the IndexType definitions — compatible deviations are updated automatically, incompatible deviations prevent startup

## Installing / Getting started

Normally you will not use this project directly, but instead set up your own index writer service instance by depending on this template, then adding specific message and operation configuration.

## Properties

| Property                                                         | Default                               | Description                                                                                                                   |
|------------------------------------------------------------------|---------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.opensearch.connection-type`         | `AWS`                                 | Connection type. Currently only `AWS` is supported.                                                                           |
| `jeap.opensearch.indexwriter.opensearch.aws.endpoint`            | —                                     | Endpoint URL of the AWS OpenSearch domain.                                                                                    |
| `jeap.opensearch.indexwriter.opensearch.aws.region`              | —                                     | AWS region of the OpenSearch domain (e.g. `eu-central-2`).                                                                    |
| `jeap.opensearch.indexwriter.search-item-provider.oauth-client`  | `search-item-provider`                | OAuth2 client registration name (from `spring.security.oauth2.client`) used to authenticate calls to the SearchItem provider. |
| `jeap.opensearch.index-writer.messages-location`                 | `classpath:/opensearch/messages.json` | Classpath or file-system location of the messages configuration file.                                                         |

### OpenSearch Connection

Configure the OpenSearch adapter via the `jeap.opensearch.indexwriter.opensearch` prefix.
The default connection type is `AWS`.

Example:

```yaml
jeap:
  opensearch:
    indexwriter:
      opensearch:
        connection-type: AWS
        aws:
          endpoint: https://my-domain.eu-central-2.es.amazonaws.com
          region: eu-central-2
```

### Remote Data (SearchItem Provider)

The service fetches SearchItems from a remote HTTP API. Configure the OAuth2 client registration used for those calls.

Example:

```yaml
jeap:
  opensearch:
    indexwriter:
      search-item-provider:
        oauth-client: my-search-item-provider-client
```

### Message Configuration

Message-to-operation mappings are loaded at startup from the location configured by `jeap.opensearch.index-writer.messages-location` (default: `classpath:/opensearch/messages.json`).

Each entry in the `messages` array maps a Kafka message type and topic to one or more index operations.

```json
{
  "messages": [
    {
      "messageName": "MyDocumentCreatedEvent",
      "topicName": "my-document-created",
      "operations": [
        {
          "indexType": "MyDocument",
          "indexOperation": "UPSERT",
          "uri": "${my.service.resource.base-uri}",
          "referenceProvider": "com.example.indexwriter.MyDocumentReferenceProvider",
          "condition": "com.example.indexwriter.MyDocumentActiveCondition",
          "featureFlag": "MY_DOCUMENT_INDEXING"
        }
      ]
    },
    {
      "messageName": "MyDocumentDeletedEvent",
      "topicName": "my-document-deleted",
      "operations": [
        {
          "indexType": "MyDocument",
          "indexOperation": "DELETE",
          "uri": "${my.service.resource.base-uri}",
          "referenceProvider": "com.example.indexwriter.MyDocumentReferenceProvider"
        }
      ]
    }
  ]
}
```

**Message fields:**

| Field         | Required | Description                                                       |
|---------------|----------|-------------------------------------------------------------------|
| `messageName` | ✅        | Simple class name of the Avro-generated message type.             |
| `topicName`   | ✅        | Kafka topic the message is consumed from.                         |
| `operations`  | ✅        | One or more index operations to execute when the message arrives. |

**Operation fields:**

| Field               | Required | Description                                                                                                                                           |
|---------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| `indexType`         | ✅        | Name of the target IndexType (must match a registered `IndexTypeDescriptor`).                                                                         |
| `indexOperation`    | ✅        | `UPSERT` or `DELETE` (case-insensitive).                                                                                                              |
| `uri`               | ✅        | URI of the SearchItem Provider endpoint. Spring property placeholders (`${...}`) are resolved at startup.                                             |
| `referenceProvider` | ✅        | Fully qualified class name of the Spring bean implementing `ReferenceProvider`, e.g. `com.example.indexwriter.MyDocumentReferenceProvider`.           |
| `condition`         | ❌        | Fully qualified class name of the Spring bean implementing `IndexingCondition`. The operation is skipped when the condition evaluates to `false`.     |
| `featureFlag`       | ❌        | Name of a jEAP feature flag. The operation is skipped when the flag is inactive.                                                                      |

## Consumer Contract Enforcement

The service enforces jEAP messaging consumer contracts at startup. Each Kafka message type configured in `messages.json` must have a matching consumer contract registered for the application.

Consumer contracts are generated at compile time by placing the `@JeapMessageConsumerContractsByTemplates` annotation on any class in the service instance module. The annotation reads the message configuration file and generates one consumer contract per configured message type.

```java
@JeapMessageConsumerContractsByTemplates(
    appName     = "my-opensearch-index-writer",
    templatesPath = "opensearch"   // directory under src/main/resources containing messages.json
)
class MyOpenSearchIndexWriterApplication {
}
```

| Attribute         | Description                                                                                                                                                                                  |
|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `appName`         | Logical application name used for the consumer contracts. Defaults to `spring.application.name` from `application.y[a]ml` when not set.                                                      |
| `templatesPath`   | Path relative to `src/main/resources` containing `messages.json`. Must match the directory of the `jeap.opensearch.index-writer.messages-location` property (default: `opensearch`). |

The annotation is processed by `jeap-messaging-contract-annotation-processor` (pulled in transitively). If the annotation is absent, or the resolved `appName` does not match `spring.application.name`, the service will refuse to start with a `NoContractException`.

## Metrics

The service publishes the following Micrometer metrics:

| Metric                                  | Type  | Tags           | Description                                                                                                                                                           |
|-----------------------------------------|-------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.indexing`  | Timer | `message_type` | Time spent processing a single indexing operation (includes skipped and failed ones). The `message_type` tag contains the name of the triggering Kafka message type.  |

## Changes

This library needs to be versioned using [Semantic Versioning](http://semver.org/) and all changes need to be documented at [CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## Index Type Registry Maven Plugin

The plugin that validates and generates artifacts from the index type registry is documented separately:
[jeap-opensearch-index-type-registry-maven-plugin/README.md](jeap-opensearch-index-type-registry-maven-plugin/README.md)

## Note

This repository is part of the open source distribution of jEAP. See [github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap) for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
