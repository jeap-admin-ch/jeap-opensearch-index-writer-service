# Configuration reference

Native OpenSearch analysis is defined by each IndexType in `settings.analysis`, rather than through
application properties. Operational index settings remain configured here. See [Native analysis](native-analysis.md).

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

## Maintenance job target policy check

| Property                                                                                  | Default | Description                                                                                                                                      |
|-------------------------------------------------------------------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.opensearch.indexwriter.maintenance.enabled` | `false` | Enables the maintenance job API, job/task index initialisation, housekeeping and the execution of upsert jobs. Requires `jeap.opensearch.indexwriter.maintenance.upsert-command-topic` and the command contracts. See [Maintenance jobs](maintenance-jobs.md) for all `maintenance.*` job settings. |
| `jeap.opensearch.indexwriter.maintenance.policy-check.verify-permission-on-startup`       | `false` | Probe ISM Explain once at startup and log an `ERROR` if it is denied. Enable it in services that use the [policy check](maintenance-target-policy-check.md). |

## Index lifecycle

By default, a deployed index has **no lifecycle policy attached** — it never rolls over and its
single physical index is never deleted by OpenSearch. This is the normal setup: the service always
creates a physical index named `{base}-000001` on first startup and writes to it indefinitely through
the write alias, whether or not any ISM policy is ever attached. The `-000001` suffix and the
template's rollover-alias setting are structural (required so the index *can* be rolled over later)
and do not themselves enable rollover; neither is a prerequisite you need to undo to run non-rolling.

With no rollover, retention and capacity become the application's own responsibility: documents are
never dropped automatically, and a single physical index keeps growing. Plan shard count and storage
for the expected data volume (see [Index template settings](#index-template-settings) above), and
decide deliberately how you delete or archive data your domain considers obsolete, rather than relying
on index age.

Verify what's actually applied to a given index with ISM Explain:

```bash
curl "https://<opensearch-host>/_plugins/_ism/explain/<physical-index>?show_policy=true"
```

No `policy_id` in the response means no policy is attached — the non-rollover default. See
[Maintenance target policy check](maintenance-target-policy-check.md) for the required permissions to
run this on a secured cluster.

### Rollover (opt-in)

Index rollover is a deliberate alternative for applications that need bounded partition size and
age-based deletion. It is managed entirely server-side by an **OpenSearch ISM (Index State
Management) policy** configured in IaC — the service itself does not trigger rollover and needs no
configuration change to support it.

IaC attaches an ISM policy via `ism_template` matching the index pattern (e.g.
`jme_decree_document_v1-*`) and configures rollover thresholds (e.g. maximum document count or age).
Once attached, subsequent partitions are created automatically by ISM when rollover conditions are
met; the index template (including the ISM rollover alias setting) continues to be managed by the
service at startup exactly as in the non-rollover case.

Changing or attaching an ISM policy only affects index creation/rollover going forward — it does not
retroactively change indices created under a different (or no) policy, and does not merge old
partitions. Treat any existing index explicitly: check what policy, if any, it already carries (via
ISM Explain above) before assuming a new policy applies to it.

If a maintenance job's target index is managed by a rollover policy, job creation refuses it — see
[Maintenance target policy check](maintenance-target-policy-check.md). The maintenance job/task
indices themselves (`jeap_indexwriter_jobs`/`jeap_indexwriter_tasks`, see
[Maintenance jobs](maintenance-jobs.md)) must stay outside any rollover/age-based-deletion policy:
their retention is handled by the service's own housekeeping, and OpenSearch deleting the index out
from under it would break job/task storage outright.

## Related

- [Startup behaviour](startup-behaviour.md)
- [OpenSearch permissions](opensearch-permissions.md)
- [Index types](index-types.md)
- [jeap-opensearch-index-writer-service](../README.md)
