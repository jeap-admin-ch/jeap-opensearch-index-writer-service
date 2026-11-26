package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageRejectedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance.MaintenanceFields.*;

@Repository
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
class OpenSearchJobRepository implements JobRepository {

    private static final String REFRESH = "refresh";
    private static final String INDEX_MAJOR_VERSION = "indexMajorVersion";
    private static final String DISPATCH_CURSOR = "dispatch_cursor";
    private static final String DISPATCH_OWNER = "dispatch_owner";
    private static final String DISPATCH_LEASE_UNTIL = "dispatch_lease_until";
    private static final String DISPATCHED_AT = "dispatched_at";

    private static final String EXCLUDE_REFERENCES = "request.originReferences";
    private static final String VISIBLE = "wait_for";

    private final MaintenanceStoreClient client;
    private final MaintenanceProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    public boolean create(Job job) {
        MaintenanceStoreClient.Result result = client.execute("PUT", "/" + index() + "/_create/" + job.jobId(),
                Map.of(REFRESH, VISIBLE), toDocument(job));
        if (result.status() == 409) {
            return false;
        }
        if (result.status() == 413) {
            throw new InvalidJobException("The job is too large for the job storage (HTTP 413); submit fewer references per job");
        }
        requireOk(result, "create job " + job.jobId());
        return true;
    }

    @Override
    public Optional<Job> find(UUID jobId, boolean withReferences) {
        Map<String, String> query = withReferences ? Map.of() : Map.of("_source_excludes", EXCLUDE_REFERENCES);
        MaintenanceStoreClient.Result result = client.execute("GET", "/" + index() + "/_doc/" + jobId, query, null);
        if (isDocumentMissing(result)) {
            return Optional.empty();
        }
        requireOk(result, "read job " + jobId);
        return Optional.of(fromHit(result.body()));
    }

    @Override
    public boolean markMaterialized(Job job) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(MATERIALIZATION_STATE, Wire.of(MaterializationState.MATERIALIZED));
        return conditionalUpdate(job, doc);
    }

    @Override
    public boolean failMaterialization(Job job, Instant completedAt) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(MATERIALIZATION_STATE, Wire.of(MaterializationState.FAILED));
        doc.put(JOB_STATE, Wire.of(JobState.COMPLETED));
        doc.put(JOB_RESULT, Wire.of(JobResult.FAILED));
        doc.put(COMPLETED_AT, completedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        return conditionalUpdate(job, doc);
    }

    @Override
    public boolean complete(Job job, JobResult result, Instant completedAt) {
        ObjectNode doc = jsonMapper.createObjectNode();
        doc.put(JOB_STATE, Wire.of(JobState.COMPLETED));
        doc.put(JOB_RESULT, Wire.of(result));
        doc.put(COMPLETED_AT, completedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        return conditionalUpdate(job, doc);
    }

    @Override
    public List<Job> findMaterializingStartedBefore(Instant startedBefore, Job after, int limit) {
        ObjectNode filter = bool(term(MATERIALIZATION_STATE, Wire.of(MaterializationState.MATERIALIZING)),
                range(STARTED_AT, startedBefore));
        return search(filter, after, limit);
    }

    @Override
    public List<Job> findOpenMaterialized(Job after, int limit) {
        ObjectNode filter = bool(term(JOB_STATE, Wire.of(JobState.OPEN)),
                term(MATERIALIZATION_STATE, Wire.of(MaterializationState.MATERIALIZED)));
        return search(filter, after, limit);
    }

    @Override
    public List<Job> findCompletedBefore(Instant completedBefore, Job after, int limit) {
        ObjectNode filter = bool(term(JOB_STATE, Wire.of(JobState.COMPLETED)), range(COMPLETED_AT, completedBefore));
        return search(filter, after, limit);
    }

    @Override
    public void delete(UUID jobId) {
        MaintenanceStoreClient.Result result = client.execute("DELETE", "/" + index() + "/_doc/" + jobId, Map.of(REFRESH, VISIBLE), null);
        if (!isDocumentMissing(result)) {
            requireOk(result, "delete job " + jobId);
        }
    }

    // Not made visible to searches right away: the housekeeping reads the dispatch state with the next run at the earliest
    @Override
    public Optional<Job> updateDispatch(Job job, Dispatch dispatch) {
        ObjectNode doc = jsonMapper.createObjectNode();
        putDispatch(doc, dispatch);
        MaintenanceStoreClient.Result result = update(job, doc, Map.of());
        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(job.withDispatch(dispatch, result.body().path("_seq_no").asLong(), result.body().path("_primary_term").asLong()));
    }

    private boolean conditionalUpdate(Job job, ObjectNode doc) {
        return update(job, doc, Map.of(REFRESH, VISIBLE)) != null;
    }

    // null if the job changed in the meantime or no longer exists
    private MaintenanceStoreClient.Result update(Job job, ObjectNode doc, Map<String, String> extraQuery) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.set("doc", doc);
        Map<String, String> query = new HashMap<>(extraQuery);
        query.put("if_seq_no", Long.toString(job.seqNo()));
        query.put("if_primary_term", Long.toString(job.primaryTerm()));
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + index() + "/_update/" + job.jobId(), query, body);
        if (result.status() == 409 || isDocumentMissing(result)) {
            return null;
        }
        requireOk(result, "update job " + job.jobId());
        return result;
    }

    private static void putDispatch(ObjectNode doc, Dispatch dispatch) {
        doc.put(DISPATCH_CURSOR, dispatch.cursor());
        doc.put(DISPATCH_OWNER, dispatch.owner());
        putInstant(doc, DISPATCH_LEASE_UNTIL, dispatch.leaseUntil());
        putInstant(doc, DISPATCHED_AT, dispatch.dispatchedAt());
    }

    // Writes null explicitly, so that a partial update clears the field
    private static void putInstant(ObjectNode doc, String field, Instant value) {
        if (value == null) {
            doc.putNull(field);
        } else {
            doc.put(field, value.truncatedTo(ChronoUnit.MILLIS).toString());
        }
    }

    private List<Job> search(ObjectNode query, Job after, int limit) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("size", limit);
        body.put("seq_no_primary_term", true);
        body.set("query", query);
        body.putArray("sort").add(STARTED_AT).add(JOB_ID);
        if (after != null) {
            body.putArray("search_after").add(after.startedAt().toEpochMilli()).add(after.jobId().toString());
        }
        MaintenanceStoreClient.Result result = client.execute("POST", "/" + index() + "/_search",
                Map.of("_source_excludes", EXCLUDE_REFERENCES), body);
        requireOk(result, "search jobs");
        List<Job> jobs = new ArrayList<>();
        for (JsonNode hit : result.body().path("hits").path("hits")) {
            jobs.add(fromHit(hit));
        }
        return jobs;
    }

    private ObjectNode toDocument(Job job) {
        ObjectNode doc = jsonMapper.createObjectNode();
        JobRequest request = job.request();
        doc.put(JOB_ID, job.jobId().toString());
        doc.put("job_type", Wire.of(request.type()));
        doc.put("request_hash", job.requestHash());
        doc.put(JOB_STATE, Wire.of(job.state()));
        doc.put("expected_task_count", job.expectedTaskCount());
        doc.put(MATERIALIZATION_STATE, Wire.of(job.materializationState()));
        doc.put(STARTED_AT, job.startedAt().truncatedTo(ChronoUnit.MILLIS).toString());
        ObjectNode submittedBy = doc.putObject("submitted_by");
        putIfPresent(submittedBy, "name", job.submittedBy().name());
        putIfPresent(submittedBy, "ext_id", job.submittedBy().extId());

        ObjectNode requestNode = doc.putObject("request");
        putIfPresent(requestNode, "messageName", request.messageName());
        putIfPresent(requestNode, "topicName", request.topicName());
        requestNode.put("indexType", request.indexType());
        if (request.indexMajorVersion() != null) {
            requestNode.put(INDEX_MAJOR_VERSION, request.indexMajorVersion());
        }
        ArrayNode references = requestNode.putArray("originReferences");
        for (JobReference reference : request.references()) {
            ObjectNode node = references.addObject();
            node.put("id", reference.id());
            putIfPresent(node, "version", reference.version());
        }

        JobTarget target = job.target();
        doc.put("major_selection", Wire.of(target.selection()));
        doc.put("resolved_index_major_version", target.indexMajorVersion());
        doc.put("target_index", target.physicalIndex());
        putIfPresent(doc, "target_index_uuid", target.physicalIndexUuid());
        doc.put("rollover_detection", target.rolloverDetection());
        putDispatch(doc, job.dispatch());
        return doc;
    }

    private Job fromHit(JsonNode hit) {
        JsonNode source = hit.path("_source");
        JsonNode requestNode = source.path("request");
        List<JobReference> references = new ArrayList<>();
        for (JsonNode reference : requestNode.path("originReferences")) {
            references.add(new JobReference(reference.path("id").asString(), text(reference, "version")));
        }
        JobRequest request = new JobRequest(Wire.parse(JobType.class, text(source, "job_type")), text(requestNode, "messageName"),
                text(requestNode, "topicName"), text(requestNode, "indexType"),
                requestNode.hasNonNull(INDEX_MAJOR_VERSION) ? requestNode.path(INDEX_MAJOR_VERSION).asInt() : null, references);
        JobTarget target = new JobTarget(Wire.parse(MajorSelection.class, text(source, "major_selection")),
                source.path("resolved_index_major_version").asInt(), text(source, "target_index"),
                text(source, "target_index_uuid"), text(source, "rollover_detection"));
        JsonNode submittedBy = source.path("submitted_by");
        return new Job(UUID.fromString(text(source, JOB_ID)), text(source, "request_hash"),
                Wire.parse(JobState.class, text(source, JOB_STATE)), Wire.parse(JobResult.class, text(source, JOB_RESULT)),
                source.path("expected_task_count").asInt(), Wire.parse(MaterializationState.class, text(source, MATERIALIZATION_STATE)),
                instant(source, STARTED_AT), instant(source, COMPLETED_AT),
                new SubmittedBy(text(submittedBy, "name"), text(submittedBy, "ext_id")), request, target,
                // Jobs stored before schema version 2 have no dispatch fields: nothing was sent for them yet
                new Dispatch(source.path(DISPATCH_CURSOR).asInt(0), text(source, DISPATCH_OWNER),
                        instant(source, DISPATCH_LEASE_UNTIL), instant(source, DISPATCHED_AT)),
                hit.path("_seq_no").asLong(), hit.path("_primary_term").asLong());
    }

    private String index() {
        return properties.getJobIndex();
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    ObjectNode term(String field, String value) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.putObject("term").put(field, value);
        return node;
    }

    ObjectNode range(String field, Instant lessThan) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.putObject("range").putObject(field).put("lt", lessThan.truncatedTo(ChronoUnit.MILLIS).toString());
        return node;
    }

    ObjectNode bool(JsonNode... filters) {
        ObjectNode node = jsonMapper.createObjectNode();
        ArrayNode array = node.putObject("bool").putArray("filter");
        for (JsonNode filter : filters) {
            array.add(filter);
        }
        return node;
    }

    // Only throttling and server errors are worth a retry; any other status (permission, payload size, mapping) is permanent
    // A missing index is a broken storage, not a missing job: answering "not found" would let the next submission
    // auto-create the index without the strict mapping
    static boolean isDocumentMissing(MaintenanceStoreClient.Result result) {
        return result.status() == 404 && !"index_not_found_exception".equals(result.body().path(ERROR).path("type").asString(""));
    }

    static void requireOk(MaintenanceStoreClient.Result result, String action) {
        if (result.ok()) {
            return;
        }
        String message = "Failed to %s: HTTP %d %s".formatted(action, result.status(), result.body());
        if (result.status() == 429 || result.status() >= 500) {
            throw new JobStorageUnavailableException(message, null);
        }
        throw new JobStorageRejectedException(message);
    }
}
