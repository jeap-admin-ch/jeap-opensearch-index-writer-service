# Maintenance jobs

Operators submit upsert or delete jobs through a YAML REST API and read a YAML report. Both job types are **run** in the background, see [Running upsert jobs](#running-upsert-jobs) and [Running delete jobs](#running-delete-jobs).

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
# delete, from schema major 1 only; without indexMajorVersion from every configured major
indexType: DecreeDocument
indexMajorVersion: 1
originReferences:
- id: '12345'
- id: '67890'
```

## Schema major

An upsert job selects exactly one `indexMajorVersion`; if omitted, the highest configured major of the index type is used. A delete job with `indexMajorVersion` deletes from that major only; if omitted, it deletes from **every** major configured for the index type in the deployed writer when the job is accepted (selection `all`). The reference `version` of an upsert is stored and reported only: it never selects a schema, a provider request or a rollback. Delete references have IDs only; a `version` or a rollback-control field is rejected.

The selection (explicit, highest or all), the majors and their targets are resolved once and stored. Later deployments and recovery never choose another target, and a major deployed after acceptance is not added to an accepted job; submit a new job to include it. Delete jobs accepted before 8.0.0 without `indexMajorVersion` keep their single highest-major target.

## Submission checks

The whole request is rejected (nothing stored) for invalid YAML or UUID, unknown schema major, duplicate IDs (regardless of version), reference IDs or versions longer than 512 characters or containing control characters, or exceeded limits (`max-references`, `max-request-bytes`). A target refused by the acceptance check (rollover policy, multi-index layout) gives `422 NOT_ALLOWED`; for a delete job targeting several majors, one refused target rejects the whole job; a write alias of the selected major that does not exist, does not point to a concrete index or has `is_write_index=false` gives `422 TARGET_INVALID`.

Same UUID and equivalent content returns the existing job, even if the configuration or `max-references` changed since the first submission; `max-request-bytes` always applies, as the body is read before it can be compared. A changed schema selector or reference version gives `409`. Submitter data and timestamps do not affect equivalence. Deliberate retries use a new UUID; idempotency ends when the retained job is cleaned up.

## Initialisation and storage

On startup the service creates the missing job and task indices (`jeap_indexwriter_jobs`, `jeap_indexwriter_tasks`) from the bundled mappings and reuses compatible existing ones. An index of an older schema version (`_meta.maintenance_schema_version`) is upgraded in place: the missing properties are added and existing documents are kept. An index of a newer version is accepted if it has every property this version needs. Versions before 7.0.0 require an exact schema version and therefore no longer start once 7.0.0 has upgraded the indices: upgrade all instances together. Before rolling back below 7.0.0, reset the version on both indices, the added properties can stay: `PUT /<index>/_mapping` with `{"_meta": {"maintenance_schema_version": 1}}`. From 7.0.0 on, newer schema versions are accepted: 7.0.0 starts on the schema version 3 indices of 8.0.0 without a reset, but it does not run delete jobs and reports only their first target. 7.0.0 cannot read a delete job with selection `all` (submitted without `indexMajorVersion`): a single such job stops the 7.0.0 housekeeping for every job and its report fails. Before rolling back to 7.0.0, delete these jobs (`major_selection: all` in the job index) together with their tasks (by `job_id` in the task index). Concurrent starts are safe. An incompatible index stops startup and is never recreated automatically. The `maintenance.*` settings are only read and validated when the feature is enabled. Housekeeping runs on its own thread; enabling maintenance does not turn on Spring scheduling for the rest of the service. Shards, replicas and refresh interval are taken from `index-settings` when an index is created; existing indices keep theirs, so change replicas or the refresh interval of an existing index directly in OpenSearch (`PUT /<index>/_settings`). If a job or task index is deleted while the service runs, requests fail with a storage error until the service is restarted and recreates it. Exclude both indices from `action.auto_create_index` on the cluster, so that a write cannot recreate them without the bundled mapping.

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
- A stall cannot tell a lost command from one still waiting in the topic, e.g. behind the commands of a large job submitted earlier, or while the consumer waits for the task index to come back. Set `task-redispatch-after` (default 2 hours) above the time the command topic needs to drain the largest backlog: with `command-consumer-concurrency: 1`, a job of 100,000 tasks takes roughly one to two hours, depending on the provider. Each job is sent again at most `task-max-redispatches` times (default 3). If it stalls once more, nothing more is sent: the job is reported as stuck (warning in the log, metric `jeap.opensearch.indexwriter.maintenance.jobs.stuck`, `stuck-since` in the report). Its open tasks stay open and still finish if their commands arrive; if the commands were really lost, submit the remaining ids as a new job.
- The command consumer reads a command topic that is new to its consumer group from the beginning, so commands sent before the first start are not skipped.
- Running a task twice is harmless: the second run writes the same current data or finds the task finished.
- A concurrent task update is acknowledged only if the retained task already has a final outcome or a scheduled retry, or has been deleted. An open task without a retry requires command redelivery.

Jobs submitted before this version (6.24.0 to 6.27.0 stored but never ran them) start running after the upgrade, with the target stored at their acceptance. There is no cancel API: check for open jobs before upgrading if they should not run.

### Command topics and contracts

`upsert-command-topic` and `delete-command-topic` are required when maintenance is enabled and must be specific to the service instance: a topic shared with another system's index writer would deliver its commands to this one. The two topics must differ, startup fails otherwise. Each topic has its own consumers: `command-consumer-concurrency` applies per topic, so up to twice that many tasks run in parallel per instance. The service principal needs Kafka ACLs to produce to and consume from both. The service instance declares the producer and consumer contracts for `UpsertSearchItemCommand` (`ch.admin.bit.jeap.messagetype.jeap:upsert-search-item-command`) and `DeleteSearchItemCommand` (`ch.admin.bit.jeap.messagetype.jeap:delete-search-item-command`) on their topics, see [consumer contracts](consumer-contracts.md#maintenance-command-contracts); startup fails if they are missing.

## Running delete jobs

Delete jobs use the same dispatch, retries, recovery and completion as upsert jobs, with one `DeleteSearchItemCommand` per task on `delete-command-topic`. For each task, the service deletes the id from every target stored with the job. It never calls the provider and has no indexing flag; the rollover policy is not checked again.

| OpenSearch answer | Outcome for the target |
|-------------------|------------------------|
| Document deleted | `deleted` |
| Document not found, index exists | `already-absent` (success) |
| Index does not exist | `failed`, `TARGET_INDEX_MISSING` |
| Write block or access denied | `failed`, `INDEX_NOT_WRITABLE` |
| Any other rejection | `failed`, `WRITE_REJECTED` |
| 429, 5xx, connection error | retried like a transient upsert error |

The id succeeds only if every target ended as `deleted` or `already-absent`; otherwise it fails, and the report shows the outcome of every target. A retry only processes targets without a final outcome; recorded outcomes are kept. After the last attempt the targets still without outcome fail with `TARGET_UNAVAILABLE`. If the service crashes after deleting a document but before saving the result, the repeated run finds the document gone and reports `already-absent` instead of `deleted`; both count as success. A business event recreating the document later is allowed: maintenance deletion is not a permanent block.

Delete jobs submitted before this version (6.24.0 to 7.0.0 stored but never ran them) start running after the upgrade, with the single target stored at their acceptance: without `indexMajorVersion` that is the highest major of that time, not every major. Deletions cannot be undone and there is no cancel API: check for open delete jobs before upgrading if they should not run.

## Reports and paging

Reports show the input, the resolved major and target, selection mode, submitter, timestamps and summary counts (`open`, `succeeded`, `failed`, `indexing-disabled`). Upsert reports have a single `target` block. Delete reports have `target-selection` and a `targets` list with one entry per major, and every id lists its `targets` with `deletion-outcome` (missing while the target is not processed yet) and the target's `error`. An open task that already failed transiently shows its `attempts` and its last error. Once the unfinished tasks of a stalled job were sent again, a `dispatch` block shows the number of rounds (`redispatches`) and, while the job is open and stuck, `stuck-since`. `limit` and `cursor` query parameters page through all IDs, including IDs whose tasks are not yet created. A storage outage or throttling returns 503 with a generic message (details are only logged), a permanent storage rejection (e.g. missing permission) 500, a job too large for the storage 400, an unknown job 404.

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
        delete-command-topic: my-system-delete-search-item-command   # required, specific to the service instance, differs from upsert-command-topic
        command-consumer-concurrency: 1   # tasks run in parallel per instance and command topic
        dispatch-batch-size: 500          # commands sent per batch, at most 10000
        dispatch-lease: PT2M
        task-max-attempts: 6              # the first try included
        task-retry-initial-backoff: PT1M
        task-retry-max-backoff: PT30M
        task-redispatch-after: PT2H       # above the time the command topic needs to drain the largest backlog
        task-max-redispatches: 3          # then the job is reported as stuck
```

When maintenance is enabled, these settings are validated at startup: sizes and durations must be positive, `task-retry-initial-backoff` must not exceed `task-retry-max-backoff`, `upsert-command-topic` and `delete-command-topic` must be set and differ, and an invalid value stops the service.

The service user needs create/read/write/delete access to both indices (see [opensearch-permissions.md](opensearch-permissions.md)).
