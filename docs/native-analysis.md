# Native analysis

An IndexType's `mappingDefinition()` resource can contain native OpenSearch `settings.analysis`
alongside `mappings`. No separate analysis accessor or jEAP analysis DSL is needed. The definition
supports `analyzer`, `normalizer`, `tokenizer`, `filter`, and `char_filter`, with native component options.
The cluster must supply plugin-specific component types where used.

Add the following settings to a definition:

```json
"settings": {
  "analysis": {
    "analyzer": {
      "folding_analyzer": {
        "type": "custom",
        "tokenizer": "standard",
        "filter": ["lowercase", "asciifolding"]
      }
    },
    "normalizer": {
      "folding_normalizer": {
        "type": "custom",
        "filter": ["lowercase", "asciifolding"]
      }
    }
  }
}
```

In `mappings.properties.data.properties`, reference them using native field parameters:

```json
"name": { "type": "text", "analyzer": "folding_analyzer" },
"code": { "type": "keyword", "normalizer": "folding_normalizer" }
```

With `data.name = "Café Müller"`, this query finds the document:

```json
{ "query": { "match": { "data.name": "cafe muller" } } }
```

The original `_source` is retained. No ASCII twin field or client-side folding is required.

## Ownership and startup

Only `mappings` and optional `settings.analysis` are permitted in the definition. Shards, replicas,
refresh intervals, aliases, and ISM settings remain writer-owned. Mapping-only definitions continue
to work. Native analysis options are preserved when constructing the template.

Before mutating an IndexType's template or mapping, the writer reads settings and mappings for all
physical indices matching this major's index pattern and indices behind the write alias. It checks
analysis, existing field analysis assignments, removed fields, and mapping-version downgrades for
that IndexType. IndexTypes are processed independently and sequentially; a later failure does not roll
back changes already applied to an earlier IndexType. This also protects handwritten IndexTypes and
legacy indices without analysis metadata. Settings scalar types are normalized for comparison because
OpenSearch returns numeric/boolean settings as strings.

The check requires `indices:monitor/settings/get` in addition to the existing mapping-read permission.
See [OpenSearch permissions](opensearch-permissions.md). The check fails startup on unreadable deployed
state or incompatible definitions, before changing the template for future rollovers.

On success, the writer combines the IndexType's native analysis with operational settings in the
template. New physical indices and rollover indices inherit it. Compatible mapping updates still
target the current physical write index; older rollover partitions are inspected but not rewritten.

## Compatibility and migration

| Change | Handling |
|---|---|
| Add a compatible field using built-in or already-installed analysis | Minor mapping update |
| Add, remove, or modify `settings.analysis` components | New IndexType major and new index |
| Change existing field analyzer/normalizer assignments | New IndexType major |
| Change existing field search-time analyzer assignments | New IndexType major by search-contract policy |

OpenSearch 3.3.2 rejects adding analysis settings to an open index. The writer does not close/reopen
indices at startup. Therefore semantically additive new components also require a major in this
implementation. This restriction is exercised by an integration test. Search-only changes do not
necessarily need retokenization, but are treated as a breaking search contract by registry policy.

Analysis and mappings are versioned together. Updating a template never reanalyzes existing documents.
For a breaking change:

1. Use registry plugin 3.19.0 or later and writer 6.2.0 or later.
2. Publish and deploy a new IndexType major. Its write alias creates a separate physical index.
3. Backfill/reindex or replay authoritative data, accounting for concurrent writes.
4. Verify document counts and search behavior.
5. Coordinate read-alias cutover and old-index retirement. Read aliases span major versions, so old
   and new indices can otherwise return duplicate logical documents or mixed search behavior.

Backfill and cutover are application operations, not automatic writer startup actions.
