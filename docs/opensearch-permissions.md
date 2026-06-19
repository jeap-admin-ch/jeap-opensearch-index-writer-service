# OpenSearch permissions

The service principal (IAM role or OpenSearch internal user) requires the following permissions to
manage index templates, create physical indices, and write documents.

## Cluster permissions

| Permission                         | Purpose                                         |
|------------------------------------|-------------------------------------------------|
| `indices:admin/index_template/put` | Create or update index templates on startup.    |
| `indices:admin/aliases/get`        | Required at cluster level for alias resolution. |
| `indices:data/write/bulk`          | Write documents via the bulk API.               |

## Index permissions

These permissions must be granted on the pattern `*` so that the service can operate on all its
index patterns without needing per-index configuration.

| Permission                     | Purpose                                                                      |
|--------------------------------|------------------------------------------------------------------------------|
| `indices:admin/create`         | Create new physical indices on first startup.                                |
| `indices:admin/aliases`        | Manage index aliases (set write alias on initial index creation).            |
| `indices:admin/aliases/exists` | Check whether an alias exists before creating the initial index.             |
| `indices:admin/aliases/get`    | Resolve which physical indices are behind a write alias.                     |
| `indices:admin/mappings/get`   | Read the current mapping of a physical index to check the schema version.    |
| `indices:admin/mapping/put`    | Update the mapping of a physical index when a new minor version is detected. |
| `indices:data/write/bulk*`     | Write documents via the bulk API (wildcard form).                            |
| `indices:data/write/bulk`      | Write documents via the bulk API.                                            |
| `indices:data/write/index`     | Index (upsert) individual documents.                                         |
| `indices:data/write/delete`    | Delete individual documents.                                                 |
| `indices:data/read/get`        | Read individual documents (used for validation).                             |
| `indices:data/read/search`     | Execute search queries (used for validation).                                |

## OpenSearch security role JSON

```json
{
  "cluster_permissions": [
    "indices:admin/index_template/put",
    "indices:admin/aliases/get",
    "indices:data/write/bulk"
  ],
  "index_permissions": [
    {
      "index_patterns": ["*"],
      "allowed_actions": [
        "indices:admin/create",
        "indices:admin/aliases",
        "indices:admin/aliases/get",
        "indices:admin/mappings/get",
        "indices:admin/mapping/put",
        "indices:data/write/bulk*",
        "indices:data/write/bulk",
        "indices:data/write/index",
        "indices:data/write/delete",
        "indices:data/read/get",
        "indices:data/read/search"
      ]
    }
  ]
}
```

## Related

- [Configuration reference](configuration.md)
- [Startup behaviour](startup-behaviour.md)
- [jeap-opensearch-index-writer-service](../README.md)
