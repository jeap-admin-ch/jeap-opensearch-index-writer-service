# Maintenance jobs

Operators submit upsert or delete jobs through a YAML REST API and read a YAML report. This version only **stores** jobs; execution is added later.

Everything on this page is active only with `jeap.opensearch.indexwriter.maintenance.enabled=true` (default `false`). When disabled there are no endpoints, no OpenAPI entries, no index initialisation, no housekeeping, and normal indexing is unaffected.

## API

| Operation | Role |
|-----------|------|
| `PUT /api/upsert-jobs/{jobId}` | `upsertjob#create` |
| `PUT /api/delete-jobs/{jobId}` | `deletejob#create` |
| `GET /api/upsert-jobs/{jobId}`, `GET /api/delete-jobs/{jobId}` | `jobreport#read` |

`PUT` accepts `application/yaml` or `application/x-yaml` (other types: 415). `GET` returns `application/yaml`, also after a restart. `jobId` is a UUID.

Both operations are published as the OpenAPI group `maintenance-jobs` (`/api-docs/maintenance-jobs`), marked as protected by the `OIDC` security scheme of the jEAP swagger starter, so Swagger UI can request a token. The service still declares its own `@OpenAPIDefinition` (title, contact). Swagger UI lists only groups once a group exists, so a service with REST endpoints outside any group needs a `GroupedOpenApi` for them.

```yaml
# upsert
message:
  messageName: JmeDecreeDocumentCreatedEvent
  topicName: jme-decree-document-created
operation:
  indexType: DecreeDocument
  indexOperation: UPSERT
  indexMajorVersion: 1
originReferences:
- id: '12345'
  version: '17'
- id: '67890'
```

```yaml
# delete
indexType: DecreeDocument
indexMajorVersion: 1
originReferences:
- id: '12345'
- id: '67890'
```

## Schema major

Each job selects exactly one `indexMajorVersion`. If omitted, the highest configured major of the index type is used. The reference `version` of an upsert is stored and reported only: it never selects a schema, a provider request or a rollback. Delete references have IDs only; a `version` or a rollback-control field is rejected.

The selection (explicit or highest), the major and the target are resolved once and stored. Later deployments and recovery never choose another target.

## Submission checks

The whole request is rejected (nothing stored) for invalid YAML or UUID, unknown schema major, duplicate IDs (regardless of version), reference IDs or versions longer than 512 characters or containing control characters, or exceeded limits (`max-references`, `max-request-bytes`). A target refused by the acceptance check (rollover policy, multi-index layout) gives `422 NOT_ALLOWED`; a write alias of the selected major that does not exist, does not point to a concrete index or has `is_write_index=false` gives `422 TARGET_INVALID`.

Same UUID and equivalent content returns the existing job, even if the configuration or `max-references` changed since the first submission; `max-request-bytes` always applies, as the body is read before it can be compared. A changed schema selector or reference version gives `409`. Submitter data and timestamps do not affect equivalence. Deliberate retries use a new UUID; idempotency ends when the retained job is cleaned up.

## Initialisation and storage

On startup the service creates the missing job and task indices (`jeap_indexwriter_jobs`, `jeap_indexwriter_tasks`) from the bundled mappings and reuses compatible existing ones. Concurrent starts are safe. An incompatible index stops startup and is never recreated automatically. The `maintenance.*` settings are only read and validated when the feature is enabled. Housekeeping runs on its own thread; enabling maintenance does not turn on Spring scheduling for the rest of the service. Shards, replicas and refresh interval are taken from `index-settings` when an index is created; existing indices keep theirs, so change replicas or the refresh interval of an existing index directly in OpenSearch (`PUT /<index>/_settings`). If a job or task index is deleted while the service runs, requests fail with a storage error until the service is restarted and recreates it. Exclude both indices from `action.auto_create_index` on the cluster, so that a write cannot recreate them without the bundled mapping.

A job is confirmed only after its input and target are stored. The PUT answers right away, so large jobs do not run into HTTP timeouts; the tasks are then created in the background in batches of `materialization-batch-size`, and `materialized-tasks` in the report shows the progress. Interrupted creation is resumed by housekeeping once the job is older than `materialization-resume-after` (default 10 minutes; set it above the time the largest job takes to prepare), without losing IDs or resetting results. A job that is not fully prepared is never processed. If the storage permanently rejects its tasks (for example a missing permission), the job is completed with result `failed` instead of being retried, its unfinished references are reported as failed with error code `TASK_CREATION_REJECTED`, the cause is logged as an error, and retention deletes it like any other finished job.

## Reports and paging

Reports show the input, the resolved major and target, selection mode, submitter, timestamps and summary counts. `limit` and `cursor` query parameters page through all IDs, including IDs whose tasks are not yet created. A storage outage or throttling returns 503 with a generic message (details are only logged), a permanent storage rejection (e.g. missing permission) 500, a job too large for the storage 400, an unknown job 404.

## Completion and retention

A job completes when every task is finished: all succeeded → `succeeded`, mixed → `partially-succeeded`, all failed → `failed`. Finished jobs and their tasks are deleted after `retention` (default 30 days) from completion. Unfinished jobs are never deleted by age.

## Configuration

```yaml
jeap:
  opensearch:
    indexwriter:
      maintenance:
        enabled: false
        max-references: 100000
        max-request-bytes: 8388608     # keep below the cluster's HTTP payload limit (10 MiB on small AWS instance types)
        materialization-batch-size: 1000   # at most 2000, so a batch stays below the cluster's HTTP payload limit
        retention: 30d
        default-page-size: 100
        max-page-size: 1000            # at most 10000; default-page-size must not exceed it
        job-index: jeap_indexwriter_jobs
        task-index: jeap_indexwriter_tasks
        index-settings:                # applied when the job and task indices are created only
          number-of-shards: 1
          number-of-replicas: 1
          refresh-interval: 1s
        housekeeping-interval: PT1M
        materialization-resume-after: PT10M
```

When maintenance is enabled, these settings are validated at startup: sizes and durations must be positive, and an invalid value stops the service.

The service user needs create/read/write/delete access to both indices (see [opensearch-permissions.md](opensearch-permissions.md)).
