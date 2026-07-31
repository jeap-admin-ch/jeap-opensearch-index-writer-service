# Configuration reference

All properties of the index writer service live under the `jeap.opensearch.indexwriter` prefix.

## OpenSearch connection

| Property                                                | Default | Description                                                                                                                                                                                               |
|---------------------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.connection.url`            | —       | URL of the OpenSearch cluster (e.g. `https://my-domain.eu-central-2.es.amazonaws.com` or `https://my-opensearch-host:9200`). Required. A URL without a scheme is interpreted as `https`.                   |
| `jeap.opensearch.indexwriter.connection.signing-region` | —       | AWS region for SigV4 request signing (e.g. `eu-central-2`). When set, the default AWS credential provider chain is used (ECS task role, EC2 instance profile, etc.) and the URL must use `https`. Leave blank for non-AWS deployments. |

Example for AWS OpenSearch Service (with IAM/SigV4 signing):

```yaml
jeap:
  opensearch:
    indexwriter:
      connection:
        url: https://my-domain.eu-central-2.es.amazonaws.com
        signing-region: eu-central-2
```

Example for a local or non-AWS OpenSearch instance:

```yaml
jeap:
  opensearch:
    indexwriter:
      connection:
        url: https://my-opensearch-host:9200
```

### URL format

The same value works for both deployment modes — the service normalizes it and hands the scheme-less
host name to the AWS transport, which always connects over `https`.

| Configured value                | Interpreted as                   |
|---------------------------------|----------------------------------|
| `https://my-host`               | `https://my-host`                |
| `https://my-host:9200`          | `https://my-host:9200`           |
| `my-host` (no scheme)           | `https://my-host`                |
| `my-host:9200` (no scheme)      | `https://my-host:9200`           |
| `http://localhost:9200`         | `http://localhost:9200`          |

The URL is validated at startup. Startup fails with an error naming the property if the URL is
missing, uses a scheme other than `http`/`https`, contains credentials, or contains a path, query or
fragment. Plain `http` is rejected when `signing-region` is set, because AWS SigV4 signed requests
are always sent over `https`.

> **Note:** Only configure `http://` explicitly for a cluster that is actually reachable without TLS,
> such as a local or test instance. Omitting the scheme always results in an encrypted connection.

## Index template settings

Template settings configure the physical index partitions created by ISM rollover. The key is the
**template name**, which is the write alias without the `_write` suffix (e.g. `jme_decree_document_v1`
for write alias `jme_decree_document_v1_write`). Use the special key `default` as a fallback for any
template not explicitly configured. If neither a specific entry nor `default` is present, startup
fails with a clear error.

> **Note:** `number-of-shards`, `number-of-replicas`, and `refresh-interval` are applied to the
> template on every startup but only take effect for new physical indices created by ISM rollover.
> Existing partitions are not affected. The mapping, by contrast, is applied immediately to existing
> indices on startup.

| Property                                                                        | Default | Description                                                                 |
|---------------------------------------------------------------------------------|---------|-----------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.index-templates.default.number-of-shards`          | —       | Fallback number of primary shards when no per-template entry is configured. |
| `jeap.opensearch.indexwriter.index-templates.default.number-of-replicas`        | —       | Fallback number of replicas when no per-template entry is configured.       |
| `jeap.opensearch.indexwriter.index-templates.default.refresh-interval`          | —       | Fallback refresh interval when no per-template entry is configured.         |
| `jeap.opensearch.indexwriter.index-templates.<templateName>.number-of-shards`   | —       | Number of primary shards for this specific index template.                  |
| `jeap.opensearch.indexwriter.index-templates.<templateName>.number-of-replicas` | —       | Number of replicas for this specific index template.                        |
| `jeap.opensearch.indexwriter.index-templates.<templateName>.refresh-interval`   | —       | Refresh interval for this specific index template.                          |

Example with a `default` fallback and a per-template override:

```yaml
jeap:
  opensearch:
    indexwriter:
      index-templates:
        default:
          number-of-shards: 1
          number-of-replicas: 1
          refresh-interval: "1s"
        jme_decree_document_v1:
          number-of-shards: 2
          number-of-replicas: 1
          refresh-interval: "5s"
```

## SearchItem provider

| Property                                                    | Default | Description                                                                       |
|-------------------------------------------------------------|---------|-----------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.search-item-provider.timeout`  | `30s`   | Connect timeout for the REST client used to call SearchItem Provider endpoints.   |

The provider base URI and the OAuth2 client ID are not configured here — they are part of the message
configuration. A client ID referenced there must be registered under
`spring.security.oauth2.client.registration`, see [Message configuration](message-configuration.md).

## Index rollover

Index rollover is managed server-side by an **OpenSearch ISM (Index State Management) policy**
configured in IaC. The service itself does not trigger rollover.

IaC attaches an ISM policy via `ism_template` matching the index pattern (e.g.
`jme_decree_document_v1-*`) and configures rollover thresholds (e.g. maximum document count or age).
The index template (including the ISM rollover alias setting) is managed by the service at startup.

The service creates the initial physical index (`{base}-000001`) on first startup if the write alias
does not exist yet. Subsequent partitions are created automatically by ISM when rollover conditions
are met.

## Related

- [Startup behaviour](startup-behaviour.md)
- [OpenSearch permissions](opensearch-permissions.md)
- [Index types](index-types.md)
- [jeap-opensearch-index-writer-service](../README.md)
