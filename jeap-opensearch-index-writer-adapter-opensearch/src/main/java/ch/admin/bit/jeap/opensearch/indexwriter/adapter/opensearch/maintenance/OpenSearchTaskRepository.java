package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageRejectedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTasksNotDeletedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskCounts;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskError;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.Wire;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance.MaintenanceFields.*;
import static ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance.OpenSearchJobRepository.*;

@Repository
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
class OpenSearchTaskRepository implements TaskRepository {

    private static final String QUERY = "query";
    private static final String FILTER = "filter";
    private static final String ATTEMPTS = "attempts";
    private static final String NEXT_ATTEMPT_AT = "next_attempt_at";
    private static final String SEQ_NO = "_seq_no";
    private static final String PRIMARY_TERM = "_primary_term";

    private final MaintenanceStoreClient client;
    private final MaintenanceProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    public void createMissing(List<JobTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (JobTask task : tasks) {
            ObjectNode action = jsonMapper.createObjectNode();
            action.putObject("create").put("_id", task.taskId().toString());
            ndjson.append(jsonMapper.writeValueAsString(action)).append('\n')
                    .append(jsonMapper.writeValueAsString(toDocument(task))).append('\n');
        }
        MaintenanceStoreClient.Result result = client.executeNdjson("/" + properties.getTaskIndex() + "/_bulk",
                Map.of("filter_path", "errors,items.*.status,items.*.error"), ndjson.toString());
        if (result.status() == 413) {
            throw new JobStorageRejectedException(
                    "A batch of %d tasks exceeds the HTTP payload limit of the cluster (HTTP 413); lower jeap.opensearch.indexwriter.maintenance.materialization-batch-size"
                            .formatted(tasks.size()));
        }
        requireOk(result, "create tasks");
        requireBulkItemsOk(result, "create", "Task rejected by the task index");
    }

    @Override
    public void refresh() {
        requireOk(client.execute("POST", "/" + properties.getTaskIndex() + "/_refresh", Map.of(), null), "refresh tasks");
    }

    @Override
    public List<JobTask> findByPositionRange(UUID jobId, int fromInclusive, int toExclusive) {
        ObjectNode range = jsonMapper.createObjectNode();
        range.putObject("range").putObject(POSITION).put("gte", fromInclusive).put("lt", toExclusive);
        return search(jobId, Math.max(toExclusive - fromInclusive, 0), List.of(termNode(JOB_ID, jobId.toString()), range), List.of());
    }

    @Override
    public List<JobTask> findDueRetries(UUID jobId, Instant dueAt, int afterPosition, int limit) {
        ObjectNode due = jsonMapper.createObjectNode();
        due.putObject("range").putObject(NEXT_ATTEMPT_AT).put("lte", dueAt.truncatedTo(ChronoUnit.MILLIS).toString());
        return search(jobId, limit, List.of(termNode(JOB_ID, jobId.toString()), termNode(TASK_STATE, Wire.of(TaskState.OPEN)),
                due, positionAfter(afterPosition)), List.of());
    }

    @Override
    public List<JobTask> findOpenUnscheduled(UUID jobId, int afterPosition, int limit) {
        ObjectNode scheduled = jsonMapper.createObjectNode();
        scheduled.putObject("exists").put("field", NEXT_ATTEMPT_AT);
        return search(jobId, limit, List.of(termNode(JOB_ID, jobId.toString()), termNode(TASK_STATE, Wire.of(TaskState.OPEN)),
                positionAfter(afterPosition)), List.of(scheduled));
    }

    private ObjectNode positionAfter(int afterPosition) {
        ObjectNode range = jsonMapper.createObjectNode();
        range.putObject("range").putObject(POSITION).put("gt", afterPosition);
        return range;
    }

    private List<JobTask> search(UUID jobId, int size, List<JsonNode> filters, List<JsonNode> mustNot) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("size", size);
        body.put("seq_no_primary_term", true);
        ObjectNode bool = body.putObject(QUERY).putObject("bool");
        filters.forEach(bool.putArray(FILTER)::add);
        if (!mustNot.isEmpty()) {
            mustNot.forEach(bool.putArray("must_not")::add);
        }
        body.putArray("sort").add(POSITION);
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + properties.getTaskIndex() + "/_search", Map.of(), body);
        requireOk(result, "read tasks of job " + jobId);
        List<JobTask> tasks = new ArrayList<>();
        for (JsonNode hit : result.body().path("hits").path("hits")) {
            tasks.add(fromHit(hit));
        }
        return tasks;
    }

    @Override
    public Optional<JobTask> find(UUID taskId) {
        MaintenanceStoreClient.Result result = client.execute("GET", "/" + properties.getTaskIndex() + "/_doc/" + taskId, Map.of(), null);
        if (isDocumentMissing(result)) {
            return Optional.empty();
        }
        requireOk(result, "read task " + taskId);
        return Optional.of(fromHit(result.body()));
    }

    @Override
    public boolean complete(JobTask task, TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                            TaskError error, Instant completedAt) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(TASK_STATE, Wire.of(state));
        doc.put(COMPLETED_AT, completedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        doc.put("actual_index_major_version", actualIndexMajorVersion);
        doc.put("actual_index_minor_version", actualIndexMinorVersion);
        doc.set(ERROR, errorNode(error));
        doc.putNull(NEXT_ATTEMPT_AT);
        return conditionalUpdate(task, doc);
    }

    @Override
    public boolean scheduleRetry(JobTask task, int attempts, Instant nextAttemptAt, TaskError lastError) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(ATTEMPTS, attempts);
        doc.put(NEXT_ATTEMPT_AT, nextAttemptAt.truncatedTo(ChronoUnit.MILLIS).toString());
        doc.set(ERROR, errorNode(lastError));
        return conditionalUpdate(task, doc);
    }

    // A task changed in the meantime (409) was already picked up by the consumer and is left alone
    @Override
    public void clearScheduledRetries(List<JobTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (JobTask task : tasks) {
            ObjectNode action = jsonMapper.createObjectNode();
            action.putObject("update").put("_id", task.taskId().toString())
                    .put("if_seq_no", task.seqNo()).put("if_primary_term", task.primaryTerm());
            ObjectNode update = jsonMapper.createObjectNode();
            update.putObject("doc").putNull(NEXT_ATTEMPT_AT);
            ndjson.append(jsonMapper.writeValueAsString(action)).append('\n')
                    .append(jsonMapper.writeValueAsString(update)).append('\n');
        }
        MaintenanceStoreClient.Result result = client.executeNdjson("/" + properties.getTaskIndex() + "/_bulk",
                Map.of("filter_path", "errors,items.*.status,items.*.error"), ndjson.toString());
        requireOk(result, "clear scheduled retries");
        requireBulkItemsOk(result, "update", "Failed to clear scheduled retry");
    }

    private boolean conditionalUpdate(JobTask task, ObjectNode doc) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.set("doc", doc);
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + properties.getTaskIndex() + "/_update/" + task.taskId(),
                Map.of("if_seq_no", Long.toString(task.seqNo()), "if_primary_term", Long.toString(task.primaryTerm())), body);
        if (result.status() == 409 || isDocumentMissing(result)) {
            return false;
        }
        requireOk(result, "update task " + task.taskId());
        return true;
    }

    private JsonNode errorNode(TaskError error) {
        if (error == null) {
            return jsonMapper.getNodeFactory().nullNode();
        }
        ObjectNode node = jsonMapper.createObjectNode();
        putIfPresent(node, "code", error.code());
        putIfPresent(node, "message", error.message());
        putIfPresent(node, "target_index", error.targetIndex());
        putIfPresent(node, "trace_id", error.traceId());
        return node;
    }

    private static void requireBulkItemsOk(MaintenanceStoreClient.Result result, String operation, String failure) {
        if (!result.body().path("errors").asBoolean(false)) {
            return;
        }
        for (JsonNode item : result.body().path("items")) {
            JsonNode outcome = item.path(operation);
            int status = outcome.path("status").asInt();
            if (status == 429 || status >= 500) {
                throw new JobStorageUnavailableException("%s: HTTP %d %s".formatted(failure, status, outcome.path(ERROR)), null);
            }
            if (status != 409 && (status < 200 || status >= 300)) {
                throw new JobStorageRejectedException("%s: HTTP %d %s".formatted(failure, status, outcome.path(ERROR)));
            }
        }
    }

    @Override
    public TaskCounts counts(UUID jobId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("size", 0);
        body.put("track_total_hits", true);
        body.putObject(QUERY).putObject("bool").putArray(FILTER).add(termNode(JOB_ID, jobId.toString()));
        ObjectNode aggs = body.putObject("aggs");
        aggs.putObject("states").putObject("terms").put("field", TASK_STATE);
        aggs.putObject("last_completed").putObject("max").put("field", COMPLETED_AT);
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + properties.getTaskIndex() + "/_search", Map.of(), body);
        requireOk(result, "count tasks of job " + jobId);
        Map<TaskState, Integer> byState = new EnumMap<>(TaskState.class);
        for (JsonNode bucket : result.body().path("aggregations").path("states").path("buckets")) {
            byState.put(Wire.parse(TaskState.class, bucket.path("key").asString()), bucket.path("doc_count").asInt());
        }
        JsonNode last = result.body().path("aggregations").path("last_completed").path("value_as_string");
        return new TaskCounts(result.body().path("hits").path("total").path("value").asInt(),
                byState.getOrDefault(TaskState.SUCCEEDED, 0), byState.getOrDefault(TaskState.FAILED, 0),
                byState.getOrDefault(TaskState.INDEXING_DISABLED, 0), last.isString() ? Instant.parse(last.stringValue()) : null);
    }

    @Override
    public void deleteByJobId(UUID jobId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.putObject(QUERY).putObject("bool").putArray(FILTER).add(termNode(JOB_ID, jobId.toString()));
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + properties.getTaskIndex() + "/_delete_by_query",
                Map.of("refresh", "true", "conflicts", "proceed"), body);
        requireOk(result, "delete tasks of job " + jobId);
        // Delete-by-query answers 200 even if some tasks were skipped or failed
        JsonNode failures = result.body().path("failures");
        long conflicts = result.body().path("version_conflicts").asLong(0);
        if (!failures.isEmpty() || conflicts > 0) {
            throw new JobTasksNotDeletedException("Not all tasks of job %s were deleted: %d version conflicts, failures %s"
                    .formatted(jobId, conflicts, failures));
        }
    }

    private ObjectNode termNode(String field, String value) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.putObject("term").put(field, value);
        return node;
    }

    private ObjectNode toDocument(JobTask task) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(JOB_ID, task.jobId().toString());
        doc.put("task_id", task.taskId().toString());
        doc.put(POSITION, task.position());
        doc.put("origin_id", task.originId());
        putIfPresent(doc, "reference_version", task.referenceVersion());
        doc.put(TASK_STATE, Wire.of(task.state()));
        doc.put("created_at", task.createdAt().truncatedTo(ChronoUnit.MILLIS).toString());
        doc.put(ATTEMPTS, task.attempts());
        return doc;
    }

    private JobTask fromHit(JsonNode hit) {
        JsonNode source = hit.path("_source");
        JsonNode error = source.path(ERROR);
        TaskError taskError = error.isObject()
                ? new TaskError(text(error, "code"), text(error, "message"), text(error, "target_index"), text(error, "trace_id")) : null;
        return new JobTask(UUID.fromString(text(source, JOB_ID)), UUID.fromString(text(source, "task_id")),
                source.path(POSITION).asInt(), text(source, "origin_id"), text(source, "reference_version"),
                Wire.parse(TaskState.class, text(source, TASK_STATE)),
                source.hasNonNull("actual_index_major_version") ? source.path("actual_index_major_version").asInt() : null,
                source.hasNonNull("actual_index_minor_version") ? source.path("actual_index_minor_version").asInt() : null,
                text(source, "deletion_outcome"), taskError, instant(source, "created_at"), instant(source, COMPLETED_AT),
                // Tasks created before schema version 2 have no attempts
                source.path(ATTEMPTS).asInt(0), instant(source, NEXT_ATTEMPT_AT),
                hit.path(SEQ_NO).asLong(), hit.path(PRIMARY_TERM).asLong());
    }
}
