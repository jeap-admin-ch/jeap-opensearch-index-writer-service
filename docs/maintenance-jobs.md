# Maintenance jobs

Operators submit upsert or delete jobs through a YAML REST API and read a YAML report. Upsert jobs are **run** in the background (see [Running upsert jobs](#running-upsert-jobs)); delete jobs are stored and reported, but not yet run.

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

On startup the service creates the missing job and task indices (`jeap_indexwriter_jobs`, `jeap_indexwriter_tasks`) from the bundled mappings and reuses compatible existing ones. An index of an older schema version (`_meta.maintenance_schema_version`) is upgraded in place: the missing properties are added and existing documents are kept. An index of a newer version is accepted if it has every property this version needs. Concurrent starts are safe. An incompatible index stops startup and is never recreated automatically. The `maintenance.*` settings are only read and validated when the feature is enabled. Housekeeping runs on its own thread; enabling maintenance does not turn on Spring scheduling for the rest of the service. Shards, replicas and refresh interval are taken from `index-settings` when an index is created; existing indices keep theirs, so change replicas or the refresh interval of an existing index directly in OpenSearch (`PUT /<index>/_settings`). If a job or task index is deleted while the service runs, requests fail with a storage error until the service is restarted and recreates it. Exclude both indices from `action.auto_create_index` on the cluster, so that a write cannot recreate them without the bundled mapping.

A job is confirmed only after its input and target are stored. The PUT answers right away, so large jobs do not run into HTTP timeouts; the tasks are then created in the background in batches of `materialization-batch-size`, and `materialized-tasks` in the report shows the progress. Interrupted creation is resumed by housekeeping once the job is older than `materialization-resume-after` (default 10 minutes; set it above the time the largest job takes to prepare), without losing IDs or resetting results. A job that is not fully prepared is never processed. If the storage permanently rejects its tasks (for example a missing permission), the job is completed with result `failed` instead of being retried, its unfinished references are reported as failed with error code `TASK_CREATION_REJECTED`, the cause is logged as an error, and retention deletes it like any other finished job.

## Running upsert jobs

Once all tasks of an upsert job are created, the service sends one `UpsertSearchItemCommand` per task to `upsert-command-topic` and consumes these commands itself. For each task it:

1. loads the stored job and task and checks that the command matches them (job type, task, origin id, version, message and topic). A command for an unknown job (e.g. one deleted after its retention) or one that does not match is logged and ignored: a command never creates or changes a job.
2. skips the task if it is already finished. A deliberate rerun needs a new job.
3. checks the `featureFlag` of the job's UPSERT operation in `messages.json`. If the flag is off, the task ends as `indexing-disabled` without calling the provider or writing; turning the flag on later does not rerun it. Without a flag there is no restriction.
4. checks that the stored target index still exists and still has the uuid stored at acceptance (cached per job for one minute). Otherwise the task fails with `TARGET_INDEX_MISSING`, so that a write cannot silently create a new empty index.
5. fetches the **current** data of the object in the job's schema major from the provider (`index_major_version`; the reference `version` is never sent) and validates it strictly like `CurrentSearchItemFetcher`.
6. replaces or creates the document in the **stored physical index** (not the write alias). The rollover policy is not checked again.
7. saves the result before acknowledging the command.

The job completes with the next housekeeping run once no task is open. Tasks ended as `indexing-disabled` count as not succeeded: all succeeded → `succeeded`, some → `partially-succeeded`, none → `failed`.

### Errors and retries

| Error code | Meaning |
|------------|---------|
| `SOURCE_NOT_FOUND` | The provider has no object with this id in the job's major. |
| `INDEX_MAJOR_VERSION_MISMATCH` | The provider answered with another major; the actual major and minor are reported. |
| `INDEX_MAJOR_VERSION_UNSUPPORTED` | The provider does not support the job's major. |
| `SCHEMA_MISMATCH` | Index type, minor, origin id or payload of the answer do not match the job's schema. |
| `SOURCE_REQUEST_REJECTED` | The provider rejected the request (4xx), or the OAuth client is not registered. |
| `CONFIGURATION_MISSING` | The job's message or its UPSERT operation is no longer configured. |
| `INDEX_TYPE_NOT_CONFIGURED` | The job's index type and major are no longer deployed. |
| `WRITE_REJECTED`, `INDEX_NOT_WRITABLE` | OpenSearch rejected the write permanently, e.g. a mapping error, a write block or a missing permission. |
| `TARGET_INDEX_MISSING` | The stored target index no longer exists or was recreated under the same name. |
| `SOURCE_UNAVAILABLE`, `TARGET_UNAVAILABLE` | The provider or OpenSearch kept failing transiently (timeout, 5xx, 429) until the attempts ran out. |
| `UNEXPECTED_ERROR` | A defect; the details are logged. |

A transient error keeps the task open: the attempt and the error are stored and the task is retried by the housekeeping, the delay doubling from `task-retry-initial-backoff` up to `task-retry-max-backoff`. After `task-max-attempts` (the first try included) the task fails with `SOURCE_UNAVAILABLE` or `TARGET_UNAVAILABLE` and the message gives the number of attempts. With the defaults the retries cover about 31 minutes; raise the settings beforehand for known maintenance windows of the provider. The provider's HTTP client may additionally retry a single request itself (e.g. a 503 once); that is not counted as an attempt.

Task failures go to the job report, never to the jEAP error handling. If the job or task cannot be read or the result cannot be saved, the command is not acknowledged and is redelivered with back-off until storage access is restored, without counting an attempt. This includes write blocks and missing permissions on the maintenance indices; a rejection by the target document index still fails the task normally.

### Recovery

- Several instances share the work: an instance takes a lease on a job (`dispatch-lease`) while it sends the job's commands in batches of `dispatch-batch-size`, and stores its progress after each batch. If it crashes, another instance continues after the lease expired; at most one batch is sent twice.
- The housekeeping sends due retries and resumes interrupted dispatches. Retry schedules are cleared before publishing commands, so dispatch cannot invalidate a consumer's result update. If publication fails after clearing the schedule, stalled-job recovery sends the unfinished task again.
- If a fully dispatched job makes no progress for `task-redispatch-after` (no task finished and no command sent), the commands of its unfinished tasks are sent again. This covers commands lost by a crash after the write but before the result was saved; such a write is then repeated. It does not count as an attempt.
- The command consumer reads a command topic that is new to its consumer group from the beginning, so commands sent before the first start are not skipped.
- Running a task twice is harmless: the second run writes the same current data or finds the task finished.
- A concurrent task update is acknowledged only if the retained task already has a final outcome or a scheduled retry, or has been deleted. An open task without a retry requires command redelivery.

Jobs submitted before this version (6.24.0 and 6.25.0 stored but never ran them) start running after the upgrade, with the target stored at their acceptance. There is no cancel API: check for open jobs before upgrading if they should not run.

### Command topic and contracts

`upsert-command-topic` is required when maintenance is enabled and must be specific to the service instance: a topic shared with another system's index writer would deliver its commands to this one. The service principal needs Kafka ACLs to produce to and consume from it. The service instance declares the producer and consumer contracts for `UpsertSearchItemCommand` (`ch.admin.bit.jeap.messagetype.jeap:upsert-search-item-command`) on this topic, see [consumer contracts](consumer-contracts.md#maintenance-command-contracts); startup fails if they are missing.

## Reports and paging

Reports show the input, the resolved major and target, selection mode, submitter, timestamps and summary counts (`open`, `succeeded`, `failed`, `indexing-disabled`). An open task that already failed transiently shows its `attempts` and its last error. `limit` and `cursor` query parameters page through all IDs, including IDs whose tasks are not yet created. A storage outage or throttling returns 503 with a generic message (details are only logged), a permanent storage rejection (e.g. missing permission) 500, a job too large for the storage 400, an unknown job 404.

## Completion and retention

A job completes when every task is finished (`succeeded`, `failed` or `indexing-disabled`): all succeeded → `succeeded`, some succeeded → `partially-succeeded`, none succeeded → `failed`. Finished jobs and their tasks are deleted after `retention` (default 30 days) from completion. Unfinished jobs are never deleted by age.

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
        upsert-command-topic: my-system-upsert-search-item-command   # required, specific to the service instance
        command-consumer-concurrency: 1   # tasks run in parallel per instance
        dispatch-batch-size: 500          # commands sent per batch, at most 10000
        dispatch-lease: PT2M
        task-max-attempts: 6              # the first try included
        task-retry-initial-backoff: PT1M
        task-retry-max-backoff: PT30M
        task-redispatch-after: PT30M
```

When maintenance is enabled, these settings are validated at startup: sizes and durations must be positive, `task-retry-initial-backoff` must not exceed `task-retry-max-backoff`, `upsert-command-topic` must be set, and an invalid value stops the service.

The service user needs create/read/write/delete access to both indices (see [opensearch-permissions.md](opensearch-permissions.md)).
