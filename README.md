# jeap-opensearch-index-writer-service

Service template for event-driven indexing of search items into OpenSearch. A service instance is
created by depending on this template, then adding index type and message configuration specific to
the owning domain.

## Key Features

- **Declarative message configuration:** Kafka message types and topics mapped to index operations via `/opensearch/messages.json`
- **Multiple operations per message:** Any number of UPSERT or DELETE operations per event
- **Reference provider pattern:** Extracts one or more business object references from each event; all references are processed independently
- **Conditional execution:** Operations guarded by `IndexingCondition` beans or jEAP feature flags
- **Schema validation:** SearchItem fields validated against the `IndexType` mapping before every write
- **Managed metadata:** `search_item.upserted_at`, `major_version`, and `minor_version` enriched automatically on every write
- **Service-owned index templates:** Index templates created and kept up to date by the service at startup
- **Error handling:** Failed operations forwarded to the jEAP error handling service

## Documentation

- [Getting started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [Configuration reference](docs/configuration.md)
- [Message configuration](docs/message-configuration.md)
- [Index types](docs/index-types.md)
- [Startup behaviour](docs/startup-behaviour.md)
- [Write operations](docs/write-operations.md)
- [OpenSearch permissions](docs/opensearch-permissions.md)
- [Consumer contracts](docs/consumer-contracts.md)
- [Metrics](docs/metrics.md)

## Changes

This library needs to be versioned using [Semantic Versioning](http://semver.org/) and all changes need to be documented at [CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## Note

This repository is part of the open source distribution of jEAP. See [github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap) for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
