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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance.MaintenanceFields.*;
import static ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance.OpenSearchJobRepository.*;

@Repository
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
class OpenSearchTaskRepository implements TaskRepository {

    private static final String QUERY = "query";
    private static final String FILTER = "filter";

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
        if (result.body().path("errors").asBoolean(false)) {
            for (JsonNode item : result.body().path("items")) {
                JsonNode create = item.path("create");
                int status = create.path("status").asInt();
                if (status == 429 || status >= 500) {
                    throw new JobStorageUnavailableException("Failed to create task: HTTP %d %s".formatted(status, create.path(ERROR)), null);
                }
                if (status != 409 && (status < 200 || status >= 300)) {
                    throw new JobStorageRejectedException("Task rejected by the task index: HTTP %d %s".formatted(status, create.path(ERROR)));
                }
            }
        }
    }

    @Override
    public void refresh() {
        requireOk(client.execute("POST", "/" + properties.getTaskIndex() + "/_refresh", Map.of(), null), "refresh tasks");
    }

    @Override
    public List<JobTask> findByPositionRange(UUID jobId, int fromInclusive, int toExclusive) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("size", Math.max(toExclusive - fromInclusive, 0));
        ObjectNode filter = body.putObject(QUERY).putObject("bool");
        filter.putArray(FILTER)
                .add(termNode(JOB_ID, jobId.toString()))
                .addObject().putObject("range").putObject(POSITION).put("gte", fromInclusive).put("lt", toExclusive);
        body.putArray("sort").add(POSITION);
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + properties.getTaskIndex() + "/_search", Map.of(), body);
        requireOk(result, "read tasks of job " + jobId);
        List<JobTask> tasks = new ArrayList<>();
        for (JsonNode hit : result.body().path("hits").path("hits")) {
            tasks.add(fromSource(hit.path("_source")));
        }
        return tasks;
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
        int succeeded = 0;
        int failed = 0;
        for (JsonNode bucket : result.body().path("aggregations").path("states").path("buckets")) {
            String state = bucket.path("key").asString();
            if (state.equals(Wire.of(TaskState.SUCCEEDED))) {
                succeeded = bucket.path("doc_count").asInt();
            } else if (state.equals(Wire.of(TaskState.FAILED))) {
                failed = bucket.path("doc_count").asInt();
            }
        }
        JsonNode last = result.body().path("aggregations").path("last_completed").path("value_as_string");
        return new TaskCounts(result.body().path("hits").path("total").path("value").asInt(), succeeded, failed,
                last.isString() ? Instant.parse(last.stringValue()) : null);
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
        return doc;
    }

    private JobTask fromSource(JsonNode source) {
        JsonNode error = source.path(ERROR);
        TaskError taskError = error.isObject()
                ? new TaskError(text(error, "code"), text(error, "message"), text(error, "target_index"), text(error, "trace_id")) : null;
        return new JobTask(UUID.fromString(text(source, JOB_ID)), UUID.fromString(text(source, "task_id")),
                source.path(POSITION).asInt(), text(source, "origin_id"), text(source, "reference_version"),
                Wire.parse(TaskState.class, text(source, TASK_STATE)),
                source.hasNonNull("actual_index_major_version") ? source.path("actual_index_major_version").asInt() : null,
                source.hasNonNull("actual_index_minor_version") ? source.path("actual_index_minor_version").asInt() : null,
                text(source, "deletion_outcome"), taskError, instant(source, "created_at"), instant(source, COMPLETED_AT));
    }
}
