# OpenSearch permissions

The service principal (IAM role or OpenSearch internal user) requires the following permissions to
manage index templates, create physical indices, and write documents.

## Cluster permissions

| Permission                         | Purpose                                         |
|------------------------------------|-------------------------------------------------|
| `indices:admin/index_template/put` | Create or update index templates on startup.    |
| `indices:admin/aliases/get`        | Required at cluster level for alias resolution. |
| `indices:data/write/bulk`          | Write documents via the bulk API.               |
| `cluster:admin/opendistro/ism/managedindex/explain` | Read the ISM policy applied to a maintenance job target ([policy check](maintenance-target-policy-check.md)). |

Without the ISM explain permission, the policy check cannot read the policy and allows the job
without verifying non-rolling operation (`policy_undetermined`); grant it so that rolling targets are refused.

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
| `indices:monitor/settings/get` | Read physical-index analysis settings before template or mapping changes. |
| `indices:admin/mapping/put`    | Update the mapping of a physical index when a new minor version is detected. |
| `indices:data/write/bulk*`     | Write documents via the bulk API (wildcard form).                            |
| `indices:data/write/bulk`      | Write documents via the bulk API.                                            |
| `indices:data/write/index`     | Index (upsert) individual documents.                                         |
| `indices:data/write/delete`    | Delete individual documents.                                                 |
| `indices:data/read/get`        | Read individual documents (used for validation).                             |
| `indices:data/read/search`     | Execute search queries (used for validation).                                |
| `indices:admin/get`            | Check whether a maintenance job target exists when ISM explain does not report it. Without it, a missing target is allowed as `policy_undetermined` instead of failing. |

## Maintenance job and task indices

Only needed when `jeap.opensearch.indexwriter.maintenance.enabled=true` ([maintenance jobs](maintenance-jobs.md)).
The service creates the job and task indices itself, so the principal needs the following additional index
permissions on the configured `job-index` and `task-index` names (the `*` pattern above already covers them):

| Permission                              | Purpose                                                              |
|-----------------------------------------|----------------------------------------------------------------------|
| `indices:admin/create`                  | Create the job and task indices on startup if missing.               |
| `indices:admin/mappings/get`            | Check that existing indices are compatible with the shipped mapping. |
| `indices:admin/mapping/put`             | Upgrade indices of an older schema version in place on startup.      |
| `indices:admin/refresh*`                | Make stored tasks visible after batch creation.                      |
| `indices:data/read/get`                 | Read a task before it is run.                                        |
| `indices:data/write/update`             | Conditionally update job and task state.                             |
| `indices:data/write/delete/byquery`     | Delete the tasks of finished jobs after the retention period.        |
| `indices:data/read/scroll*`             | Required by delete-by-query.                                         |

Upsert and delete jobs write to the physical indices stored with the job (e.g. `jme_decree_document_v1-000001`), not through
the write alias; upsert jobs also read the `index.uuid` setting before writing. The `*` pattern above covers this; a role
restricted to alias names must also cover the physical index names with `indices:data/write/index` and
`indices:monitor/settings/get` for upsert jobs and `indices:data/write/delete` for delete jobs. Without it, every target of a
delete job fails with `INDEX_NOT_WRITABLE`.

## OpenSearch security role JSON

```json
{
  "cluster_permissions": [
    "indices:admin/index_template/put",
    "indices:admin/aliases/get",
    "indices:data/write/bulk",
    "cluster:admin/opendistro/ism/managedindex/explain"
  ],
  "index_permissions": [
    {
      "index_patterns": ["*"],
      "allowed_actions": [
        "indices:admin/create",
        "indices:admin/aliases",
        "indices:admin/aliases/get",
        "indices:admin/mappings/get",
        "indices:monitor/settings/get",
        "indices:admin/mapping/put",
        "indices:data/write/bulk*",
        "indices:data/write/bulk",
        "indices:data/write/index",
        "indices:data/write/delete",
        "indices:data/write/update",
        "indices:data/write/delete/byquery",
        "indices:admin/refresh*",
        "indices:data/read/scroll*",
        "indices:data/read/get",
        "indices:data/read/search",
        "indices:admin/get"
      ]
    }
  ]
}
```

## Related

- [Configuration reference](configuration.md)
- [Startup behaviour](startup-behaviour.md)
- [Maintenance jobs](maintenance-jobs.md)
- [Maintenance job target policy check](maintenance-target-policy-check.md)
- [jeap-opensearch-index-writer-service](../README.md)
