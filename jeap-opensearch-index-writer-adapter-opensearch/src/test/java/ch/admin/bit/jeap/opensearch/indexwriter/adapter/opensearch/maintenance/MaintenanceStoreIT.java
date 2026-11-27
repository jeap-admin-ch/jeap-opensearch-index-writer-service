package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.OpenSearchIndexWriterException;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TargetWriteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskErrorCode;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTargetLayoutNotSupportedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.*;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

@Testcontainers
class MaintenanceStoreIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("docker-hub.nexus.bit.admin.ch/opensearchproject/opensearch:3.3.2")
                    .asCompatibleSubstituteFor("opensearchproject/opensearch"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200));

    private static JsonMapper jsonMapper;
    private static MaintenanceStoreClient client;
    private static OpenSearchClient openSearchClient;

    @BeforeAll
    static void setUp() {
        jsonMapper = JsonMapper.builder().disable(WRITE_DATES_AS_TIMESTAMPS).build();
        openSearchClient = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("http", OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200)))
                .setMapper(new JacksonJsonpMapper(jsonMapper))
                .build());
        client = new MaintenanceStoreClient(openSearchClient, jsonMapper);
    }

    private static MaintenanceProperties properties(String prefix) {
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setJobIndex(prefix + "_jobs");
        properties.setTaskIndex(prefix + "_tasks");
        return properties;
    }

    private static void initialize(MaintenanceProperties properties) {
        new MaintenanceIndexInitializer(client, properties, jsonMapper).initialize();
    }

    private static Job job(UUID id, int expectedTasks, List<JobReference> references) {
        JobRequest request = new JobRequest(JobType.UPSERT, "Msg", "topic", "DecreeDocument", 1, references);
        return new Job(id, "hash", JobState.OPEN, null, expectedTasks, MaterializationState.MATERIALIZING,
                Instant.parse("2026-10-23T09:00:00Z"), null, new SubmittedBy("Operator", "123"), request,
                new JobTarget(MajorSelection.EXPLICIT, 1, "idx-000001", "uuid", JobTarget.ROLLOVER_NOT_DETECTED), Dispatch.NONE, 0, 0);
    }

    private static JobTask task(UUID jobId, int position, String originId) {
        return JobTask.open(jobId, position, originId, position == 0 ? "17" : null, Instant.parse("2026-10-23T09:00:01Z"));
    }

    @Test
    void housekeepingQueries_pageThroughAllCandidatesInStableOrder() {
        MaintenanceProperties properties = properties("paging");
        initialize(properties);
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);
        Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
        Instant cutoff = t0.plus(Duration.ofDays(1));
        Map<String, List<UUID>> expected = new java.util.HashMap<>();
        StringBuilder bulk = new StringBuilder();
        for (String group : List.of("materializing", "open", "completed", "late")) {
            for (int i = 0; i < 25; i++) {
                UUID id = UUID.randomUUID();
                expected.computeIfAbsent(group, g -> new ArrayList<>()).add(id);
                // Pairs share a start time, so the job id must break the tie
                Instant started = group.equals("late") ? cutoff.plus(Duration.ofHours(1)) : t0.plus(Duration.ofMinutes(i / 2));
                bulk.append("{\"create\":{\"_id\":\"").append(id).append("\"}}\n")
                        .append(jobDocument(id, group, started, group.equals("completed") ? t0.plus(Duration.ofMinutes(i)) : null)).append('\n');
            }
        }
        MaintenanceStoreClient.Result stored = client.executeNdjson("/" + properties.getJobIndex() + "/_bulk", Map.of("refresh", "true"), bulk.toString());
        assertThat(stored.body().path("errors").asBoolean(true)).as(stored.body().toString()).isFalse();

        assertPagedInOrder(after -> jobs.findMaterializingStartedBefore(cutoff, after, 10), expected.get("materializing"));
        assertPagedInOrder(after -> jobs.findOpenMaterialized(after, 10), expected.get("open"));
        assertPagedInOrder(after -> jobs.findCompletedBefore(cutoff, after, 10), expected.get("completed"));
    }

    @Test
    void conditionalUpdates_succeedOnASearchHitAndFailOnAStaleVersion() {
        MaintenanceProperties properties = properties("conditional");
        initialize(properties);
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);
        UUID completeId = UUID.randomUUID();
        UUID failId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
        String bulk = "{\"create\":{\"_id\":\"" + completeId + "\"}}\n" + jobDocument(completeId, "open", t0, null) + "\n"
                + "{\"create\":{\"_id\":\"" + failId + "\"}}\n" + jobDocument(failId, "materializing", t0.plusSeconds(1), null) + "\n";
        client.executeNdjson("/" + properties.getJobIndex() + "/_bulk", Map.of("refresh", "true"), bulk);

        Job fromHit = jobs.findOpenMaterialized(null, 10).getFirst();
        assertThat(jobs.complete(fromHit, JobResult.SUCCEEDED, t0)).isTrue();
        assertThat(jobs.complete(fromHit, JobResult.FAILED, t0)).as("stale version").isFalse();
        Job completed = jobs.find(completeId, false).orElseThrow();
        assertThat(completed.state()).isEqualTo(JobState.COMPLETED);
        assertThat(completed.result()).isEqualTo(JobResult.SUCCEEDED);

        Job materializing = jobs.findMaterializingStartedBefore(t0.plus(Duration.ofDays(1)), null, 10).getFirst();
        assertThat(jobs.failMaterialization(materializing, t0)).isTrue();
        assertThat(jobs.failMaterialization(materializing, t0)).as("stale version").isFalse();
        Job failed = jobs.find(failId, false).orElseThrow();
        assertThat(failed.materializationState()).isEqualTo(MaterializationState.FAILED);
        assertThat(failed.result()).isEqualTo(JobResult.FAILED);
        assertThat(jobs.findMaterializingStartedBefore(t0.plus(Duration.ofDays(1)), null, 10)).isEmpty();
    }

    private static void assertPagedInOrder(java.util.function.Function<Job, List<Job>> page, List<UUID> expectedIds) {
        List<Job> all = new ArrayList<>();
        Job after = null;
        List<Job> current;
        do {
            current = page.apply(after);
            all.addAll(current);
            after = current.isEmpty() ? null : current.getLast();
        } while (current.size() == 10);

        assertThat(all).extracting(Job::jobId).containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(all).isSortedAccordingTo(java.util.Comparator.comparing(Job::startedAt).thenComparing(j -> j.jobId().toString()));
    }

    // Written directly, as storing over 100 jobs one by one with refresh=wait_for would take minutes
    private static String jobDocument(UUID id, String group, Instant started, Instant completed) {
        String state = group.equals("completed") ? "completed" : "open";
        String materialization = group.equals("open") || group.equals("completed") ? "materialized" : "materializing";
        return ("{\"job_id\":\"%s\",\"job_type\":\"upsert\",\"request_hash\":\"hash\",\"job_state\":\"%s\",%s"
                + "\"expected_task_count\":1,\"materialization_state\":\"%s\",\"started_at\":\"%s\","
                + "\"submitted_by\":{\"name\":\"Operator\"},\"request\":{\"indexType\":\"DecreeDocument\",\"originReferences\":[{\"id\":\"a\"}]},"
                + "\"major_selection\":\"explicit\",\"resolved_index_major_version\":1,\"target_index\":\"idx-000001\","
                + "\"rollover_detection\":\"not-detected\"}")
                .formatted(id, state, completed == null ? "" : "\"job_result\":\"succeeded\",\"completed_at\":\"" + completed + "\",",
                        materialization, started);
    }

    @Test
    void initializer_createsThenReusesIndices() {
        MaintenanceProperties properties = properties("reuse");

        initialize(properties);
        initialize(properties);

        assertThat(client.execute("HEAD", "/reuse_jobs", Map.of(), null).ok()).isTrue();
        assertThat(client.execute("HEAD", "/reuse_tasks", Map.of(), null).ok()).isTrue();
    }

    @Test
    void initializer_concurrentStartsDoNotFail() {
        MaintenanceProperties properties = properties("concurrent");

        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            starts.add(CompletableFuture.runAsync(() -> initialize(properties)));
        }

        CompletableFuture.allOf(starts.toArray(CompletableFuture[]::new)).join();
        assertThat(client.execute("HEAD", "/concurrent_tasks", Map.of(), null).ok()).isTrue();
    }

    @Test
    void initializer_incompatibleIndexStopsStartupAndIsNotRecreated() {
        MaintenanceProperties properties = properties("incompat");
        client.execute("PUT", "/incompat_jobs", Map.of(), jsonMapper.readTree(
                "{\"mappings\":{\"properties\":{\"job_id\":{\"type\":\"text\"}}}}"));

        assertThatThrownBy(() -> initialize(properties)).isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("is incompatible");

        assertThat(client.execute("GET", "/incompat_jobs/_mapping", Map.of(), null).body().toString())
                .contains("\"text\"");
    }

    @Test
    void jobRepository_createFindAndConditionalUpdate() {
        MaintenanceProperties properties = properties("jobs");
        initialize(properties);
        OpenSearchJobRepository repository = new OpenSearchJobRepository(client, properties, jsonMapper);
        UUID id = UUID.randomUUID();
        Job job = job(id, 2, List.of(new JobReference("a", "17"), new JobReference("b", null)));

        assertThat(repository.create(job)).isTrue();
        assertThat(repository.create(job)).isFalse();

        Job stored = repository.find(id, true).orElseThrow();
        assertThat(stored.request().references()).containsExactly(new JobReference("a", "17"), new JobReference("b", null));
        assertThat(stored.target().physicalIndex()).isEqualTo("idx-000001");
        assertThat(repository.find(id, false).orElseThrow().request().references()).isEmpty();
        assertThat(repository.find(UUID.randomUUID(), false)).isEmpty();

        assertThat(repository.markMaterialized(stored)).isTrue();
        assertThat(repository.markMaterialized(stored)).isFalse();
        assertThat(repository.find(id, false).orElseThrow().materializationState()).isEqualTo(MaterializationState.MATERIALIZED);

        repository.delete(id);
        assertThat(repository.find(id, false)).isEmpty();
    }

    @Test
    void taskRepository_createMissingIsIdempotentAndKeepsResults() {
        MaintenanceProperties properties = properties("tasks");
        initialize(properties);
        OpenSearchTaskRepository repository = new OpenSearchTaskRepository(client, properties, jsonMapper);
        UUID jobId = UUID.randomUUID();
        List<JobTask> tasks = List.of(task(jobId, 0, "a"), task(jobId, 1, "b"), task(jobId, 2, "c"));

        repository.createMissing(tasks.subList(0, 2));
        repository.createMissing(tasks);
        repository.refresh();

        List<JobTask> all = repository.findByPositionRange(jobId, 0, 10);
        assertThat(all).extracting(JobTask::originId).containsExactly("a", "b", "c");
        assertThat(all.get(0).referenceVersion()).isEqualTo("17");
        assertThat(repository.findByPositionRange(jobId, 1, 2)).extracting(JobTask::originId).containsExactly("b");
        assertThat(repository.counts(jobId).total()).isEqualTo(3);

        repository.deleteByJobId(jobId);
        repository.refresh();
        assertThat(repository.counts(jobId).total()).isZero();
    }

    @Test
    void largeJob_isCompletelyStoredAndPaged() {
        MaintenanceProperties properties = properties("large");
        initialize(properties);
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);
        OpenSearchTaskRepository tasks = new OpenSearchTaskRepository(client, properties, jsonMapper);
        UUID id = UUID.randomUUID();
        int size = 100_000;
        List<JobReference> references = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            references.add(new JobReference("id-" + i, null));
        }

        assertThat(jobs.create(job(id, size, references))).isTrue();
        for (int from = 0; from < size; from += 1000) {
            List<JobTask> batch = new ArrayList<>(1000);
            for (int p = from; p < from + 1000; p++) {
                batch.add(task(id, p, "id-" + p));
            }
            tasks.createMissing(batch);
        }
        tasks.refresh();

        assertThat(jobs.find(id, true).orElseThrow().request().references()).hasSize(size);
        assertThat(tasks.counts(id).total()).isEqualTo(size);
        assertThat(tasks.findByPositionRange(id, 99_900, 100_000)).hasSize(100);
    }

    @Test
    void targetResolver_resolvesSingleIndexAndRejectsMultiIndexAlias() {
        OpenSearchJobTargetResolver resolver = new OpenSearchJobTargetResolver(client);
        client.execute("PUT", "/res-000001", Map.of(), jsonMapper.readTree("{\"aliases\":{\"res_v1_write\":{}}}"));
        client.execute("PUT", "/multi-000001", Map.of(), jsonMapper.readTree("{\"aliases\":{\"multi_v1_write\":{}}}"));
        client.execute("PUT", "/multi-000002", Map.of(), jsonMapper.readTree("{\"aliases\":{\"multi_v1_write\":{}}}"));

        ResolvedTarget target = resolver.resolveWriteTarget("res_v1_write");

        assertThat(target.physicalIndex()).isEqualTo("res-000001");
        assertThat(target.physicalIndexUuid()).isNotBlank();
        assertThatThrownBy(() -> resolver.resolveWriteTarget("multi_v1_write"))
                .isInstanceOf(JobTargetLayoutNotSupportedException.class);

        client.execute("PUT", "/broken-000001", Map.of(), jsonMapper.readTree("{\"aliases\":{\"broken_v1_write\":{\"is_write_index\":false}}}"));
        assertThatThrownBy(() -> resolver.resolveWriteTarget("broken_v1_write"))
                .isInstanceOf(JobExceptions.JobTargetNotWritableException.class);
    }

    @Test
    void permanentRejection_isNotReportedAsUnavailable() {
        MaintenanceProperties properties = properties("blocked");
        initialize(properties);
        client.execute("PUT", "/" + properties.getJobIndex() + "/_settings", Map.of(),
                jsonMapper.readTree("{\"index.blocks.write\":true}"));
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);

        assertThatThrownBy(() -> jobs.create(job(UUID.randomUUID(), 1, List.of(new JobReference("a", null)))))
                .isInstanceOf(JobExceptions.JobStorageRejectedException.class);
    }

    @Test
    void invalidIndexSettings_areReportedAsCreationFailureNotAsIncompatibleIndex() {
        MaintenanceProperties properties = properties("badsettings");
        properties.getIndexSettings().setRefreshInterval("not-a-duration");

        assertThatThrownBy(() -> initialize(properties)).isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("could not be created").hasMessageContaining("index-settings");
    }

    @Test
    void indexSettings_areTakenFromTheProperties() {
        MaintenanceProperties properties = properties("settings");
        properties.getIndexSettings().setNumberOfReplicas(0);
        properties.getIndexSettings().setRefreshInterval("5s");
        initialize(properties);

        MaintenanceStoreClient.Result settings = client.execute("GET", "/" + properties.getTaskIndex() + "/_settings", Map.of(), null);

        assertThat(settings.body().path(properties.getTaskIndex()).path("settings").path("index").path("number_of_replicas").asString())
                .isEqualTo("0");
        assertThat(settings.body().path(properties.getTaskIndex()).path("settings").path("index").path("refresh_interval").asString())
                .isEqualTo("5s");
    }

    @Test
    void missingJobIndex_isAStorageFailureNotAMissingJob() {
        MaintenanceProperties properties = properties("never_initialized");
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jobs.find(id, false)).isInstanceOf(JobExceptions.JobStorageRejectedException.class);
        assertThatThrownBy(() -> jobs.delete(id)).isInstanceOf(JobExceptions.JobStorageRejectedException.class);
    }

    @Test
    void missingJob_isReportedAsMissing() {
        MaintenanceProperties properties = properties("missing_job");
        initialize(properties);
        OpenSearchJobRepository jobs = new OpenSearchJobRepository(client, properties, jsonMapper);

        assertThat(jobs.find(UUID.randomUUID(), false)).isEmpty();
    }

    @Test
    void unreachableStorage_isReportedAsUnavailable() {
        OpenSearchClient unreachable = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("http", "localhost", 1))
                .setMapper(new JacksonJsonpMapper(jsonMapper))
                .build());
        OpenSearchJobRepository repository = new OpenSearchJobRepository(
                new MaintenanceStoreClient(unreachable, jsonMapper), properties("down"), jsonMapper);

        assertThatThrownBy(() -> repository.find(UUID.randomUUID(), false))
                .isInstanceOf(JobStorageUnavailableException.class);
    }

    @Test
    void initializer_upgradesIndicesOfSchemaVersion1InPlace() {
        MaintenanceProperties properties = properties("upgrade");
        createVersion1Index("upgrade_jobs", "maintenance-index-mappings/job-index.json",
                "dispatch_cursor", "dispatch_owner", "dispatch_lease_until", "dispatched_at");
        createVersion1Index("upgrade_tasks", "maintenance-index-mappings/task-index.json", "attempts", "next_attempt_at");
        UUID jobId = UUID.randomUUID();
        JobTask stored = task(jobId, 0, "a");
        client.execute("PUT", "/upgrade_tasks/_doc/" + stored.taskId(), Map.of("refresh", "true"), jsonMapper.readTree("""
                {"job_id":"%s","task_id":"%s","position":0,"origin_id":"a","task_state":"open","created_at":"2026-10-23T09:00:01.000Z"}
                """.formatted(jobId, stored.taskId())));

        initialize(properties);
        initialize(properties);

        JsonNode tasks = client.execute("GET", "/upgrade_tasks/_mapping", Map.of(), null).body().path("upgrade_tasks").path("mappings");
        assertThat(tasks.path("_meta").path("maintenance_schema_version").asInt()).isEqualTo(2);
        assertThat(tasks.path("properties").has("attempts")).isTrue();
        assertThat(tasks.path("properties").has("next_attempt_at")).isTrue();
        JsonNode jobs = client.execute("GET", "/upgrade_jobs/_mapping", Map.of(), null).body().path("upgrade_jobs").path("mappings");
        assertThat(jobs.path("properties").has("dispatch_cursor")).isTrue();
        JobTask read = new OpenSearchTaskRepository(client, properties, jsonMapper).find(stored.taskId()).orElseThrow();
        assertThat(read.attempts()).as("a task stored before the upgrade has no attempts").isZero();
    }

    @Test
    void initializer_acceptsANewerSchemaVersionThatHasEveryProperty() {
        MaintenanceProperties properties = properties("newer");
        initialize(properties);
        client.execute("PUT", "/newer_tasks/_mapping", Map.of(), jsonMapper.readTree("""
                {"_meta":{"maintenance_schema_version":3},"properties":{"future_field":{"type":"keyword"}}}"""));

        initialize(properties);
    }

    @Test
    void jobRepository_updateDispatchIsConditional() {
        MaintenanceProperties properties = properties("dispatch");
        initialize(properties);
        OpenSearchJobRepository repository = new OpenSearchJobRepository(client, properties, jsonMapper);
        UUID id = UUID.randomUUID();
        repository.create(job(id, 1, List.of(new JobReference("a", null))));
        Job stored = repository.find(id, false).orElseThrow();
        assertThat(stored.dispatch()).isEqualTo(Dispatch.NONE);
        Instant lease = Instant.parse("2026-11-26T12:02:00Z");
        Dispatch leased = new Dispatch(0, "instance-1", lease, null, 0, null);

        Job updated = repository.updateDispatch(stored, leased).orElseThrow();
        Job advanced = repository.updateDispatch(updated, new Dispatch(1, null, null, lease, 2, lease)).orElseThrow();

        assertThat(repository.updateDispatch(stored, leased)).as("stale version").isEmpty();
        assertThat(repository.find(id, false).orElseThrow().dispatch()).isEqualTo(new Dispatch(1, null, null, lease, 2, lease));
        assertThat(advanced.isFullyDispatched()).isTrue();
    }

    @Test
    void taskRepository_resultsRetriesAndOpenTaskQueries() {
        MaintenanceProperties properties = properties("results");
        initialize(properties);
        OpenSearchTaskRepository repository = new OpenSearchTaskRepository(client, properties, jsonMapper);
        UUID jobId = UUID.randomUUID();
        repository.createMissing(List.of(task(jobId, 0, "a"), task(jobId, 1, "b"), task(jobId, 2, "c")));
        Instant due = Instant.parse("2026-11-26T12:01:00Z");
        TaskError transientError = new TaskError("SOURCE_UNAVAILABLE", "HTTP 503", null, "trace-1");
        JobTask a = repository.find(JobModel.taskId(jobId, "a")).orElseThrow();

        assertThat(repository.scheduleRetry(a, 1, due, transientError)).isTrue();
        assertThat(repository.scheduleRetry(a, 1, due, transientError)).as("stale version").isFalse();
        assertThat(repository.complete(repository.find(JobModel.taskId(jobId, "b")).orElseThrow(), TaskState.SUCCEEDED, 1, 2, null, due)).isTrue();
        assertThat(repository.complete(repository.find(JobModel.taskId(jobId, "c")).orElseThrow(), TaskState.INDEXING_DISABLED, null, null, null, due)).isTrue();
        repository.refresh();

        JobTask retried = repository.find(a.taskId()).orElseThrow();
        assertThat(retried.attempts()).isEqualTo(1);
        assertThat(retried.nextAttemptAt()).isEqualTo(due);
        assertThat(retried.error()).isEqualTo(transientError);
        assertThat(repository.findDueRetries(jobId, due, -1, 10)).extracting(JobTask::originId).containsExactly("a");
        assertThat(repository.findDueRetries(jobId, due.minusSeconds(1), -1, 10)).isEmpty();
        assertThat(repository.findOpenUnscheduled(jobId, -1, 10)).isEmpty();
        TaskCounts counts = repository.counts(jobId);
        assertThat(counts.succeeded()).isEqualTo(1);
        assertThat(counts.indexingDisabled()).isEqualTo(1);
        assertThat(counts.finished()).isEqualTo(2);
        JobTask b = repository.find(JobModel.taskId(jobId, "b")).orElseThrow();
        assertThat(b.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(b.actualIndexMinorVersion()).isEqualTo(2);

        repository.clearScheduledRetries(List.of(retried));
        repository.refresh();
        assertThat(repository.findOpenUnscheduled(jobId, -1, 10)).extracting(JobTask::originId).containsExactly("a");

        JobTask cleared = repository.find(a.taskId()).orElseThrow();
        assertThat(repository.complete(cleared, TaskState.SUCCEEDED, 1, 0, null, due)).isTrue();
        assertThat(repository.find(a.taskId()).orElseThrow().error()).as("the last transient error is cleared").isNull();
    }

    @Test
    void targetWriter_writesToThePhysicalIndexAndReportsMissingOrBlockedIndices() {
        client.execute("PUT", "/target_v1-000001", Map.of(), jsonMapper.readTree("""
                {"mappings":{"dynamic":false,"properties":{"origin":{"properties":{"id":{"type":"keyword"}}}}}}"""));
        OpenSearchMaintenanceTargetWriter writer = new OpenSearchMaintenanceTargetWriter(openSearchClient, client);
        var item = new SearchItem<>(new Origin("doc-1", null, null, null, null, null, Map.of()), Map.of("field", "value"))
                .withMetadata(new SearchItemMetadata(Instant.parse("2026-11-26T12:00:00Z"), 1, 0));

        assertThat(writer.findIndexUuid("target_v1-000001")).isPresent();
        assertThat(writer.findIndexUuid("missing_v1-000001")).isEmpty();
        writer.upsert("target_v1-000001", "doc-1", item);
        assertThat(client.execute("GET", "/target_v1-000001/_doc/doc-1", Map.of(), null).body().path("found").asBoolean()).isTrue();

        client.execute("PUT", "/target_v1-000001/_settings", Map.of(), jsonMapper.readTree("{\"index.blocks.write\":true}"));
        assertThatThrownBy(() -> writer.upsert("target_v1-000001", "doc-2", item))
                .isInstanceOfSatisfying(TargetWriteException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(TaskErrorCode.INDEX_NOT_WRITABLE);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    private static void createVersion1Index(String index, String mappingResource, String... newProperties) {
        try (var in = MaintenanceStoreIT.class.getClassLoader().getResourceAsStream(mappingResource)) {
            ObjectNode definition = (ObjectNode) jsonMapper.readTree(in);
            ObjectNode mappings = (ObjectNode) definition.path("mappings");
            ((ObjectNode) mappings.path("_meta")).put("maintenance_schema_version", 1);
            for (String property : newProperties) {
                ((ObjectNode) mappings.path("properties")).remove(property);
            }
            assertThat(client.execute("PUT", "/" + index, Map.of(), definition).ok()).isTrue();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
