# jeap-opensearch-index-writer-service

Service template to provide event-driven indexing of search items into OpenSearch. A service instance can be created by depending on this template, then adding specific message and operation configuration.

## Key Features

- **Declarative Message Configuration:** Messages wih operation map an event type and topic to an index type and index operation (`UPSERT` or `DELETE`)
- **Multiple Operations:** Any number of operations can be configured per service instance
- **Reference Provider:** Each operation references a provider that extracts the business object reference (OriginType, Id, optional Version) from the incoming event
- **SearchItem Provider:** Each operation declares the host of the SearchItem Provider API from which the search item is fetched
- **Conditional Execution:** operations can reference a condition; the operation is only executed if the event satisfies it
- **Feature Flags:** operations can be guarded by a feature flag; the operation is skipped when the flag is inactive
- **Schema Validation:** Before writing, the service verifies that the SearchItem returned by the provider is compatible with the target IndexType definition
- **Managed Timestamps:** The service sets `search_item.created`, `search_item.modified`, and `search_item.minor_version` on each SearchItem
- **Index Alias Writes:** All writes use the `IndexWriteAlias` of the IndexTypeVersion, supporting platform-managed index rotation
- **Error Handling:** If an index operation fails, the triggering event is forwarded to the jEAP error handling service
- **Startup Mapping Validation:** On startup, the service compares the index mappings against the IndexType definitions — compatible deviations are updated automatically, incompatible deviations prevent startup

## Installing / Getting started

Normally you will not use this project directly, but instead set up your own index writer service instance by depending on this template, then adding specific message and operation configuration.

## Configuration

### OpenSearch Connection

Configure the OpenSearch adapter via the `jeap.opensearch.indexwriter.opensearch` prefix.
The default connection type is `AWS`.

| Property                                                 | Default | Description                                                |
|----------------------------------------------------------|---------|------------------------------------------------------------|
| `jeap.opensearch.indexwriter.opensearch.connection-type` | `AWS`   | Connection type. Currently only `AWS` is supported.        |
| `jeap.opensearch.indexwriter.opensearch.aws.endpoint`    | —       | Endpoint URL of the AWS OpenSearch domain.                 |
| `jeap.opensearch.indexwriter.opensearch.aws.region`      | —       | AWS region of the OpenSearch domain (e.g. `eu-central-2`). |

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

## Changes

This library needs to be versioned using [Semantic Versioning](http://semver.org/) and all changes need to be documented at [CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## Note

This repository is part of the open source distribution of jEAP. See [github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap) for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
