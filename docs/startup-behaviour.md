# Startup behaviour

On startup, `IndexMappingUpdater` iterates over every registered `IndexTypeDescriptor` and calls
`ensureIndexReady` for each one. This performs two operations against OpenSearch: creating or
updating the index template, and validating or updating the mapping on the current write index.

## 1. Index template

The service **creates and manages the index template itself**. On every startup it unconditionally
applies the template via `PUT`, creating it if it does not exist or overwriting it if it does. The
template always contains the current mapping, the configured shard/replica/refresh settings, the
read alias, and the ISM rollover alias setting.

The template name is derived from the write alias by stripping the `_write` suffix:

| Write alias                      | Template name              | Index pattern                |
|----------------------------------|----------------------------|------------------------------|
| `jme_decree_document_v1_write`   | `jme_decree_document_v1`   | `jme_decree_document_v1-*`   |
| `precious_registration_v1_write` | `precious_registration_v1` | `precious_registration_v1-*` |

Template settings are looked up under
`jeap.opensearch.indexwriter.index-templates.<templateName>` (template name without `_write`),
falling back to the `default` entry. If neither is present, startup fails with a clear error.

> **Why the write alias is not in the template:** When ISM performs a rollover it creates the new
> partition from the template. If the template contained the write alias, the new partition would
> receive it immediately — while the old one still holds it — causing OpenSearch to reject the
> rollover with *"Rollover alias can point to multiple indices, found duplicated alias"*. The write
> alias is set explicitly only when the initial physical index is created, and is then owned
> exclusively by ISM.

> **Settings changes and existing partitions:** Changes to `number-of-shards`,
> `number-of-replicas`, and `refresh-interval` are written to the template on every startup but
> only take effect for new partitions created by ISM rollover. Existing partitions are not affected.
> The mapping, by contrast, is applied to both the template and all existing physical indices
> immediately on startup.

## 2. Index mapping on existing physical indices

After updating the template, the service resolves the physical write index pointed to by the write
alias and checks the `_meta.schema_version` stored in its mapping. The write index is the one
carrying `is_write_index: true` on the alias; if no index carries that flag (older setups with a
single physical index), the single physical index is used.

| Condition                                                                      | Action                                                                                                                                                   |
|--------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Write alias does not exist                                                     | Create initial physical index `{base}-000001` with write alias (`is_write_index: true`). The template automatically applies settings and the read alias. |
| Mapping version matches current minor version                                  | No action — index is left untouched.                                                                                                                     |
| Mapping version differs                                                        | Push updated mapping to the write index via `PUT /{physicalIndex}/_mapping`.                                                                             |
| OpenSearch returns `cluster_block_exception` (e.g. disk flood-stage watermark) | Log a warning and continue startup — the mapping update is applied on the next startup once the block is removed.                                        |

## Related

- [Architecture](architecture.md)
- [Configuration reference](configuration.md)
- [Index types](index-types.md)
- [OpenSearch permissions](opensearch-permissions.md)
- [jeap-opensearch-index-writer-service](../README.md)
