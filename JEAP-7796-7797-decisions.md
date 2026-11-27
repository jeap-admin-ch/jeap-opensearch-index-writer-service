# Decisions for JEAP-7796 and JEAP-7797

Decisions taken on 2026-11-26 on top of the stories:

- [JEAP-7796 — Run upsert maintenance jobs](https://jira.bit.admin.ch/browse/JEAP-7796)
- [JEAP-7797 — Run delete maintenance jobs](https://jira.bit.admin.ch/browse/JEAP-7797)

Both stories build on JEAP-7793, JEAP-7794 and JEAP-7795, which are already merged. Since release 6.24.0 the service stores maintenance jobs and creates one task per ID, but it never runs them. These two stories add the execution.

## Terms used below

- **Job:** one submitted upsert or delete request, stored in the job index (`jeap_indexwriter_jobs`).
- **Task:** the work for one ID of a job, stored in the task index (`jeap_indexwriter_tasks`). Until now a task ends as `succeeded` or `failed`.
- **Major:** the schema major version of an index type (`IndexType.majorVersion()`). Each major has its own write alias and physical index, e.g. `jme_decree_document_v1_write` → `jme_decree_document_v1-000001`.
- **Target:** the physical index of one major, resolved and stored when the job is accepted. Execution always writes to the stored target and never resolves it again.
- **Dispatch:** sending one Kafka command (`UpsertSearchItemCommand` or `DeleteSearchItemCommand`) per task. The service consumes its own commands and runs the tasks.
- **Housekeeping:** the background job that already runs every `housekeeping-interval` (default 1 min) on every instance. It resumes task creation, completes jobs and deletes expired jobs. New steps are added to it.
- **Transient error:** an error that may go away by itself: provider timeout, provider not reachable, provider 5xx or 429, OpenSearch 5xx or 429, I/O errors. Everything else (provider 404, schema mismatch, OpenSearch 4xx such as a mapping rejection or a write block) is **permanent**.

---

## Part 1: Decided by the team

### 1. A delete job without `indexMajorVersion` deletes from all configured majors

**Before:** In 6.24.0 an omitted `indexMajorVersion` resolves to the highest configured major, for both job types. Each job has exactly one target. The [Confluence analysis](https://confluence.bit.admin.ch/spaces/BLUE/pages/1878092636/Analyze+Opensearch+Index+Wartung) (v14) says the same.

**Now:** JEAP-7797 changes this for **delete jobs only**:

- **Explicit major:** the ID is deleted from that major only. This is unchanged.
- **Omitted major:** the ID is deleted from every major configured for the index type in the deployed writer. That means every `IndexType` bean with this origin type that exists when the job is accepted. Majors that exist only in the registry, or are deployed later, are not included.
- The set of targets is stored with the job and never changes. A major deployed after acceptance is not added; a new job is needed for it.
- Reports show the selection as `all`.

**Example:** `DecreeDocument` has majors 1 and 2, and document `12345` exists in both.

```yaml
# Deletes only the major-1 copy; the major-2 copy stays
indexType: DecreeDocument
indexMajorVersion: 1
originReferences:
- id: '12345'
```

```yaml
# Deletes both copies
indexType: DecreeDocument
originReferences:
- id: '12345'
```

**Implemented in:** JEAP-7797.

### 2. Delete reports always use target lists

**Problem:** The 6.24.0 delete report has one `target:` block per job and one `deletion-outcome` per ID. That can't show a job that deletes from several majors.

**Decision:** Delete reports always list targets, also for jobs with an explicit single major:

- **Job level:** a `targets:` list with one entry per stored target: major, physical index and rollover detection. The selection (`explicit`, `all`, or `highest` for jobs stored before JEAP-7797) is shown once for the job.
- **Per ID:** the overall `state` (`open`, `succeeded`, `failed`) plus a `targets:` list with one entry per target:
  - `index-major-version` and `physical-index`
  - `deletion-outcome`: `deleted`, `already-absent` or `failed`. It is missing while that target hasn't been processed yet.
  - `error` (code, message, target index, trace ID) if that target failed.
- **Removed from delete reports:** the job-level `target:` block and the per-ID `deletion-outcome`.
- **Upsert reports are unchanged:** they keep the single `target:` block, because an upsert always has exactly one target.

**Why:** Clients such as the CLI (JEAP-7798) only have to handle one report shape per job type, whatever the selection. Breaking the 6.24.0 delete report is accepted: the API is opt-in, it was released two days ago, it never ran a job, and the CLI isn't built yet.

**Example:** ID `12345` was deleted from major 1, but major 2 has a write block.

```yaml
target-selection: all
targets:
- index-major-version: 1
  physical-index: jme_decree_document_v1-000001
  rollover: not-detected
- index-major-version: 2
  physical-index: jme_decree_document_v2-000001
  rollover: not-detected
originReferences:
- task-id: c19c04b7-cb22-4323-ad5d-fab96f6bfec8
  id: '12345'
  state: failed
  targets:
  - index-major-version: 1
    physical-index: jme_decree_document_v1-000001
    deletion-outcome: deleted
  - index-major-version: 2
    physical-index: jme_decree_document_v2-000001
    deletion-outcome: failed
    error:
      code: INDEX_NOT_WRITABLE
      message: The index jme_decree_document_v2-000001 has a write block; document 12345 was not deleted.
      target-index: jme_decree_document_v2-000001
```

The field names are the planned spelling. They are finalised with the OpenAPI DTOs during implementation.

**Implemented in:** JEAP-7797.

### 3. A switched-off indexing flag ends the task as `INDEXING_DISABLED`

**Context:** JEAP-7796 says "check the operation flag". This is the togglz `featureFlag` that `messages.json` can set on the UPSERT operation of the job's message and index type. For normal event-driven indexing, a switched-off flag means the event is silently skipped.

**Decision:**

- The flag is checked each time an upsert task runs, not when the job is submitted.
- If the flag is off, the task ends in a **new final task state, `indexing-disabled`**. The provider isn't called and nothing is written.
- If the operation has no flag configured, there is no restriction.
- Turning the flag back on does **not** rerun these tasks. They are final, like `succeeded` and `failed`. To process the IDs, the operator submits a new job.
- Delete jobs have no message operation, so they have no flag and no check.

**Why a separate state and not `failed`:** A switched-off flag is a deliberate configuration choice, not an error. The operator should be able to tell "nothing was written because indexing is switched off" apart from real failures.

**Implemented in:** JEAP-7796.

### 4. `INDEXING_DISABLED` counts as "not succeeded" in the job result

**Decision:**

- The report summary gets its own `indexing-disabled` count next to `open`, `succeeded` and `failed`.
- A job completes once no task is `open`. Disabled tasks count as finished.
- The job result counts disabled tasks as not succeeded:
  - every task `succeeded` → `succeeded`
  - at least one `succeeded`, but not all → `partially-succeeded`
  - none `succeeded` (all `failed` and/or `indexing-disabled`) → `failed`
- The possible job results stay the same: `succeeded`, `partially-succeeded`, `failed`.

**Example:**

```yaml
summary:
  total-references: 3
  materialized-tasks: 3
  open: 0
  succeeded: 1
  failed: 1
  indexing-disabled: 1
job-result: partially-succeeded
```

**Implemented in:** JEAP-7796.

### 5. Transient errors: retry with back-off, then fail after a maximum number of attempts

**Revised:** first decided as "retry until it works". That was changed, because a single ID whose source always answers 5xx would have kept its job open forever. Open jobs are never deleted by retention, and there is no cancel API.

**Decision:**

- If a task hits a transient error, it stays `open`. The service records the attempt and retries later with a growing delay. The mechanics are in decision 19.
- If an attempt succeeds, or hits a permanent error, the task ends normally.
- After `task-max-attempts` (default 6, the first try included), the task ends as `failed` with the code of the last transient error:
  - `SOURCE_UNAVAILABLE` if the provider was the problem;
  - `TARGET_UNAVAILABLE` if OpenSearch was the problem.

  The message says how many attempts were made and gives the last error, e.g. `Gave up after 6 attempts: provider answered HTTP 503`.
- **Delete jobs:** attempts are counted per target. When a target runs out of attempts, only that target becomes `failed`. Targets that already succeeded keep their outcome. The ID as a whole then ends as `failed` (decision 22).
- Every job eventually finishes.
- jEAP error handling (error topic, error-handling service) is not used. JEAP-7796 requires every task failure to appear in the job report.
- Failures of the task index itself are not limited this way (decision 19a).

**Implemented in:** JEAP-7796 for upsert. JEAP-7797 reuses it per target.

### 6. Version

- JEAP-7796 ships as a **major** release, **7.0.0** (revised 2026-11-27 after the PR review by Matthias Fritschi; first decided as a minor bump). Services that already enable maintenance must add the command topic and new contract annotations, and instances before 7.0.0 cannot start once the indices are on schema version 2. Master had meanwhile released 6.26.0 (JEAP-7943) and was at 6.27.0.
- The bump for JEAP-7797 is decided when its branch is created. It is probably minor again: the delete report and selection changes only affect the opt-in maintenance API (decision 2).

### 7. Delivery story by story

- **JEAP-7796 comes first and is complete on its own.** It contains everything both stories share (storage upgrade, dispatch, Kafka consumer, retries, the `indexing-disabled` state) plus upsert execution. In this release, delete jobs can still be submitted and reported, but they don't run.
- **JEAP-7797 follows** on its own branch, created from master after JEAP-7796 is merged. It adds the multi-major submission, delete execution and the new delete report.

---

## Part 2: Design decisions from the implementation plan

These were proposed during planning and not objected to. They fill gaps the stories leave open.

### Submission

#### 8. All majors: if one major's target is refused, the whole job is rejected

A delete job without a major resolves several targets. Each one goes through the existing JEAP-7795 acceptance check: write alias exists and points to one concrete index, no rollover policy, no multi-index layout. If any target fails the check:

- The whole request is rejected: `422 NOT_ALLOWED` or `422 TARGET_INVALID`, as for a single target today.
- The error message names the affected major and index.
- Nothing is stored and no commands are sent.

**Why:** The Confluence analysis explicitly rules out "mixed-major partial acceptance". If some majors were silently left out, an operator would believe the ID was deleted everywhere. Instead, the operator can resubmit with an explicit `indexMajorVersion` for the majors that are allowed.

#### 9. An upsert without a major still uses the highest major

Decision 1 applies to delete jobs only. An upsert asks the provider for data in exactly one schema major, so it has exactly one target. Without `operation.indexMajorVersion` that remains the highest configured major, as in 6.24.0.

#### 10. Resubmitting with the same job UUID returns the stored job unchanged

The request hash (used to recognise an equivalent retry) includes the selector **as submitted**: an explicit major, or "omitted". It doesn't include the resolved majors. If a new major is deployed and an operator resubmits the same delete job with the same UUID, the stored job comes back with its original targets. The majors are not resolved again. This matches the 6.24.0 idempotency rules; a deliberate new run needs a new UUID.

### Existing data

#### 11. Jobs stored before the upgrade start running, with their original targets

Releases 6.24.0 to 6.25.0 store jobs but never run them, so a cluster may already hold accepted, open jobs.

- After deploying JEAP-7796, stored open **upsert** jobs start running.
- After deploying JEAP-7797, stored open **delete** jobs start running.
- They use the targets stored when they were accepted, possibly days earlier. The rollover check is not repeated (the stories say execution never rechecks).
- A delete job stored without a major before JEAP-7797 has selection `highest` and one target. It is **not** widened to all majors; the accepted target set never changes.
- There is no cancel API. Operators who don't want such a job to run should check for open jobs (via the report API) before upgrading.

#### 12. The job and task indices are upgraded in place at startup

**Problem:** Both indices use a strict mapping with `_meta.maintenance_schema_version: 1`. Today startup stops if an existing index lacks a property, so any new field would block every service that already enabled maintenance.

**Decision:**

- At startup, if an existing index has an older schema version and only lacks properties, the service adds the missing properties with `PUT /<index>/_mapping` and sets the new schema version.
- Existing documents are not touched.
- Concurrent startups are safe, because the same mapping update can be applied several times.
- A real conflict (a property exists with a different type) still stops startup. The index is never deleted or recreated automatically.
- The service user needs permission to update the mapping of both indices. This goes into `docs/opensearch-permissions.md`.

**Planned versions:**
- **v2 (JEAP-7796):** job fields `dispatch_cursor`, `dispatch_owner`, `dispatch_lease_until`, `dispatched_at`; task fields `attempts`, `next_attempt_at`.
- **v3 (JEAP-7797):** job field `targets[]`; task field `target_outcomes[]`.

### Dispatch and commands

#### 13. Commands are dispatched per job, using a lease and a cursor

Several service instances run housekeeping at the same time. Without coordination, each instance would send every command of a job.

- **Lease:** before dispatching, an instance takes a lease on the job: owner and expiry stored on the job document, set with a conditional update (OpenSearch `if_seq_no`/`if_primary_term`). Only the lease holder dispatches.
- **Batches:** the holder reads tasks in position order, `dispatch-batch-size` at a time. It sends a command for each task that is still `open`, waits for Kafka to confirm the whole batch, then moves `dispatch_cursor` forward and renews the lease.
- **Crash:** the lease expires and another instance continues from the cursor. At most one batch is sent twice, which is harmless (decision 18).
- **When it starts:** right after a job's tasks are created. Housekeeping resumes dispatch that stopped.

**Why per job and not per task:** Tracking dispatch on each task would cost one extra write per task, i.e. 100,000 writes for a full-size job. The task fields `command_id` and `dispatch_state`, reserved in the 6.24.0 mapping, stay unused.

#### 14. Command identity

The commands follow the PAS `CreateArtifactCommand` pattern (jEAP envelope, reference, payload):

- `idempotenceId` = task ID. Every re-send of the same task carries the same identity, as the story requires ("Preserve logical command/idempotence identity across redelivery").
- `processId` = job ID.
- The publisher's system and service name come from the existing jEAP messaging configuration.
- No Kafka key: commands spread across partitions, and no ordering is needed (the story adds no ordering or key requirement).

#### 15. Command topics are configured per service instance and required

- New settings:
  - `jeap.opensearch.indexwriter.maintenance.upsert-command-topic` (JEAP-7796)
  - `jeap.opensearch.indexwriter.maintenance.delete-command-topic` (JEAP-7797)
- If maintenance is enabled and a topic is missing, startup fails with a clear message.
- **No defaults.** The registry's default topic names (`upsert-search-item-command`, `delete-search-item-command`) would be the same for every system's index writer. On a shared Kafka cluster, one system's writer would then receive another system's commands.
- Each instance needs Kafka ACLs to produce to and consume from its topics. This is documented.

#### 16. Service instances declare the command contracts

jEAP messaging only allows producing and consuming a message type if a contract exists for it. Because the topics differ per instance, the library can't declare the contracts itself. Each service instance adds them, for example:

```java
@JeapMessageProducerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = "jme-opensearch-upsert-command")
@JeapMessageConsumerContract(value = UpsertSearchItemCommand.TypeRef.class, topic = "jme-opensearch-upsert-command")
```

When maintenance is enabled, startup checks that the contracts exist and fails with a clear message otherwise. `docs/consumer-contracts.md` explains this.

### Consumer and recovery

#### 17. A separate Kafka consumer for the commands

- Each command topic gets its own listener container, built from the jEAP container factory. Contract checks and Avro deserialisation therefore work as for events.
- `command-consumer-concurrency` (default 1) limits how many tasks run in parallel per instance. This keeps maintenance from starving normal event indexing and from overloading the provider.
- The container has its own error handler and never publishes to the jEAP error topic. Task results go to the job report (decision 5).
- Only storage failures of the job and task indices may escape: unavailable (5xx, 429, unreachable) and, added during implementation, rejected (write block, missing permission), as well as a concurrent task update that left no durable outcome. In these cases the command is not acknowledged and Kafka redelivers it with back-off (decision 19a). Everything else is logged and skipped.

#### 18. Commands are checked against the stored job; finished tasks never run again

For each command, the consumer loads the job and the task by their IDs from the command and checks that:

- the job exists and its type matches the command type;
- the task belongs to the job;
- the command's origin ID equals the task's origin ID;
- for upsert: the command's message and topic name equal the stored job's.

Then:

- **Unknown job** (e.g. already deleted after the retention period) **or mismatch:** a warning is logged, the command is acknowledged and nothing runs. A command never recreates or changes a job; the stored state is authoritative.
- **Task already finished** (`succeeded`, `failed` or `indexing-disabled`): the command is acknowledged and nothing runs. This covers duplicate deliveries and re-sends. The story says a deliberate retry needs a new job.
- **Otherwise:** the task runs, its result is saved with a conditional update on the task, and only then is the command acknowledged.

#### 19. Retry mechanics

When an attempt hits a transient error, the consumer:

1. stores on the task: the attempt count (`attempts`), the last error, and the time of the next attempt (`next_attempt_at`). The task stays `open`.
2. acknowledges the command. The Kafka partition is not blocked, so other tasks keep running.

A housekeeping step re-sends the command once `next_attempt_at` has passed.

**Delays:** they double from `task-retry-initial-backoff` (default 1 min) up to `task-retry-max-backoff` (default 30 min). With the defaults:

| Attempt | Runs about | Elapsed since first try |
|---|---|---|
| 1 | immediately | 0 min |
| 2 | 1 min later | 1 min |
| 3 | 2 min later | 3 min |
| 4 | 4 min later | 7 min |
| 5 | 8 min later | 15 min |
| 6 | 16 min later | 31 min |

If attempt 6 also hits a transient error, the task fails (decision 5). Retries are picked up on the next housekeeping run, so real delays can be up to `housekeeping-interval` (default 1 min) longer.

**Stalled jobs:** a second housekeeping step catches commands that never produced a result, e.g. lost by a crash before the result was saved. It applies when a job:

- is fully dispatched,
- still has open tasks without a pending retry, and
- has made no progress for `task-redispatch-after` (default 2 hours; first 30 min).

The commands of those tasks are sent again. This does **not** count as an attempt, because the task never reported an error.

**Cap on re-send rounds (added 2026-11-27 after the PR review):** a stall cannot tell a lost command from one still queued behind another large job, or from a consumer waiting for the task index to come back. Without a limit, such a job would be sent again every period and the copies would pile up in the topic. Therefore:

- each job is sent again at most `task-max-redispatches` times (default 3);
- if it stalls once more, nothing more is sent and the job is marked stuck (stored once on the job, warning in the log, metric `jeap.opensearch.indexwriter.maintenance.jobs.stuck`, `stuck-since` in the report);
- the job is **not** failed: its open tasks stay open and still finish if their commands arrive. If the commands were really lost, the operator submits the remaining IDs as a new job;
- `task-redispatch-after` must exceed the time the command topic needs to drain the largest backlog (documented), hence the higher default.

**Report:** open tasks show `attempts` and the last error, so an operator can see why a job is still running.

#### 18a. A new command topic is read from the beginning (added during implementation)

The command consumer sets `auto.offset.reset=earliest`. Without it, commands sent before the consumer group first joined the topic (e.g. right after the first deployment) would be skipped and only recovered 30 minutes later by the stalled-job check (decision 19). Reading old commands again is harmless: finished tasks and unknown jobs are not run (decision 18). This showed up in the integration test, where the topic is created on the first send.

#### 19a. If the task index itself is unavailable, the command is redelivered until it is back

The attempt limit applies to provider and target errors only. If the task index can't be written (OpenSearch down or throttling on the task index), no attempt and no result can be recorded. The command is then not acknowledged, and Kafka redelivers it with back-off until the task index is writable again. Nothing is lost: the task stays `open`, and decision 18 prevents it from running twice once it has a result.

#### 20. Job completion stays in housekeeping

The existing housekeeping step already completes a job once every task is final, counting tasks per state. It now also counts `indexing-disabled`, and computes the result as in decision 4.

The consumer does **not** check for job completion after each task. That would mean a count query per command, which is expensive for large jobs. A job is therefore marked completed within about one `housekeeping-interval` after its last task finishes.

### Writes

#### 21. Upserts write to the stored physical index, not the write alias

- An upsert writes to the physical index stored at acceptance (e.g. `jme_decree_document_v1-000001`), not to `jme_decree_document_v1_write`. The story requires using the stored index; the alias could point somewhere else later.
- The service user therefore needs write permission on the physical index names. Index patterns usually cover them; this is documented.

**Check before writing (added later; the team accepted the proposal):**

- **Problem:** OpenSearch creates a missing index on write unless `action.auto_create_index` forbids it. If the stored target was deleted, an upsert would quietly create a new, empty index with that name, without the read alias, instead of failing.
- **Check:** before an upsert writes, the service checks that the target index exists and still has the UUID stored at acceptance (`target_index_uuid`). This also catches an index that was deleted and recreated under the same name.
- **Cost:** the result is cached per job and instance for 1 minute, so a large job adds about one extra OpenSearch call per minute and instance, not one per task.
- **Outcomes:**
  - index missing or UUID differs → the task fails with `TARGET_INDEX_MISSING` and nothing is written;
  - the check itself hits a transient error (OpenSearch 429/5xx) → retried like any other transient error (decisions 5 and 19).
- **Jobs without a stored UUID** (possible for jobs stored before this release) only get the existence check.
- **Deletes don't run this check:** a delete on a missing index returns `index_not_found`, creates nothing, and already ends as `TARGET_INDEX_MISSING` (decision 22).

#### 22. Delete outcomes per target

For each stored target, the consumer deletes the document by ID:

| OpenSearch answer | Outcome for this target |
|---|---|
| Document deleted | `deleted` (success) |
| Document not found, index exists | `already-absent` (success) |
| Index does not exist | `failed`, `TARGET_INDEX_MISSING` |
| Write block or 403 | `failed`, `INDEX_NOT_WRITABLE` |
| Other 4xx | `failed`, `WRITE_REJECTED` |
| 429, 5xx, I/O error | transient: retried (decisions 5 and 19) |

- The ID is `succeeded` only if every target ended as `deleted` or `already-absent`. If any target failed, the ID is `failed`, and the report still shows every target's outcome.
- All outcomes of a run are saved in **one** update of the task.
- A retry only handles targets that have no final outcome yet. Recorded outcomes are never reset.
- **Known side effect:** if the service crashes after deleting a document but before saving the result, the replay finds the document gone and reports `already-absent` instead of `deleted`. Both count as success. This is documented.
- Deletes never call the provider and never check a feature flag.

### Logistics

#### 23. Order of work across repositories

1. The registry branch `feature/JEAP-7796` in `jeap-message-type-registry` (both command schemas) is reviewed and merged first, so the Java bindings get published. Done: `upsert-search-item-command:1.0.0` is published and used by JEAP-7796.
2. Then this service: JEAP-7796, then JEAP-7797 (decision 7).
3. The `jme-opensearch-example` changes (example configuration, walkthrough, integration tests) follow each story after its release, because the example needs the published version.

---

## Effects of delivering story by story

- **Delete jobs submitted while only JEAP-7796 is deployed** still follow the 6.24.0 rule: without a major they get the highest major, fixed at acceptance. They start running once JEAP-7797 is deployed, with that single target (decision 11).
- **New required configuration with JEAP-7797:** services that already enable maintenance must add `delete-command-topic` and the delete command contracts before upgrading, or startup fails (decisions 15 and 16). The JEAP-7797 changelog calls this out.
- **Two schema upgrades:** v1 → v2 with JEAP-7796 and v2 → v3 with JEAP-7797, both using the mechanism from decision 12.

## Known risk

- **Long outages fail tasks.** A provider or OpenSearch outage longer than the retry window (about 31 min with the defaults) fails the affected tasks with `SOURCE_UNAVAILABLE` or `TARGET_UNAVAILABLE`. The operator resubmits the failed IDs in a new job. For large jobs or known maintenance windows, `task-max-attempts` and `task-retry-max-backoff` can be raised beforehand.

## Appendix: planned task error codes

| Code | Job type | Meaning |
|---|---|---|
| `SOURCE_NOT_FOUND` | upsert | The provider has no object with this ID in the job's major. |
| `INDEX_MAJOR_VERSION_MISMATCH` | upsert | The provider answered with a different major than the job's. |
| `INDEX_MAJOR_VERSION_UNSUPPORTED` | upsert | The provider answers that it does not support the job's major (added during implementation). |
| `SCHEMA_MISMATCH` | upsert | Index type, minor, origin ID or payload of the answer don't match the job's schema. |
| `SOURCE_REQUEST_REJECTED` | upsert | The provider rejected the request permanently (other 4xx), or the OAuth client is not registered (added during implementation). |
| `CONFIGURATION_MISSING` | upsert | The job's message or its UPSERT operation is no longer in `messages.json`. |
| `INDEX_TYPE_NOT_CONFIGURED` | upsert | The job's index type and major are no longer deployed. |
| `SOURCE_UNAVAILABLE` | upsert | The provider kept failing transiently until the attempts ran out. |
| `WRITE_REJECTED` | both | OpenSearch rejected the write or delete permanently (e.g. a mapping error). |
| `INDEX_NOT_WRITABLE` | both | The target index has a write block, or access is denied. |
| `TARGET_INDEX_MISSING` | both | The stored target index no longer exists. For upsert also: it was recreated under the same name and its UUID differs from the stored one (decision 21). |
| `TARGET_UNAVAILABLE` | both | OpenSearch kept failing transiently until the attempts ran out. |
| `UNEXPECTED_ERROR` | both | A defect while running the task; the details are logged. Ends the task so the job does not stay open forever (added during implementation). |
| `TASK_CREATION_REJECTED` | both | Exists since 6.24.0: the task index refused to create the job's tasks. |

`indexing-disabled` (decision 3) is a task state, not an error code.
