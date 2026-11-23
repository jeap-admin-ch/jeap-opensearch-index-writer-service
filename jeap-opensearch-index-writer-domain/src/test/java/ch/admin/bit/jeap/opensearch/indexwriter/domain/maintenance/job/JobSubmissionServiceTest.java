package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobRequest;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptance;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptanceCheck;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSubmissionServiceTest {

    private final InMemoryJobStorage storage = new InMemoryJobStorage();

    private static JobRequest upsert(Integer major, JobReference... references) {
        return new JobRequest(JobType.UPSERT, "Msg", "topic", "DecreeDocument", major, List.of(references));
    }

    @Test
    void requestHash_ignoresOrderButNotVersionOrSelector() {
        String base = JobSubmissionService.requestHash(upsert(1, new JobReference("a", "17"), new JobReference("b", null)));

        assertThat(JobSubmissionService.requestHash(upsert(1, new JobReference("b", null), new JobReference("a", "17"))))
                .isEqualTo(base);
        assertThat(JobSubmissionService.requestHash(upsert(1, new JobReference("a", "18"), new JobReference("b", null))))
                .isNotEqualTo(base);
        assertThat(JobSubmissionService.requestHash(upsert(null, new JobReference("a", "17"), new JobReference("b", null))))
                .isNotEqualTo(base);
        assertThat(JobSubmissionService.requestHash(upsert(2, new JobReference("a", "17"), new JobReference("b", null))))
                .isNotEqualTo(base);
    }

    @Test
    void submit_rejectsDuplicateIdsEvenWithDifferentVersions() {
        JobSubmissionService service = serviceWithLimit(10);

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference("a", "1"), new JobReference("a", "2")), null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void submit_rejectsTooManyReferences() {
        JobSubmissionService service = serviceWithLimit(1);

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference("a", null), new JobReference("b", null)), null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void submit_rejectsVersionOnDeleteReference() {
        JobSubmissionService service = serviceWithLimit(10);
        JobRequest request = new JobRequest(JobType.DELETE, null, null, "DecreeDocument", 1, List.of(new JobReference("a", "1")));

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(), request, null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("must not have a version");
    }

    @Test
    void submit_rejectsOverlongIdOrVersion() {
        JobSubmissionService service = serviceWithLimit(10);
        String tooLong = "x".repeat(JobSubmissionService.MAX_REFERENCE_LENGTH + 1);

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference(tooLong, null)), null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("longer than");
        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference("a", tooLong)), null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("longer than");
    }

    @Test
    void submit_rejectsControlCharactersInIdOrVersion() {
        JobSubmissionService service = serviceWithLimit(10);

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference("a\u0001b", null)), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("control character");
        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(),
                upsert(1, new JobReference("a", "1\n2")), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("control character");
    }

    @Test
    void submit_equivalentRetryReturnsStoredJobEvenIfLimitsChanged() {
        JobRequest request = upsert(1, new JobReference("a", null), new JobReference("b", null));
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null));
        JobSubmissionService service = serviceWithLimit(1);

        JobModel.Job job = service.submit(new JobModel.JobSubmission(jobId, request, null));

        assertThat(job.jobId()).isEqualTo(jobId);
        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.MATERIALIZED);
        assertThat(storage.tasksOf(jobId)).hasSize(2);
    }

    @Test
    void submit_sameIdWithDifferentContentIsConflict() {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, upsert(1, new JobReference("a", null)), JobState.OPEN,
                MaterializationState.MATERIALIZED, java.time.Instant.now(), null));
        JobSubmissionService service = serviceWithLimit(10);

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(jobId, upsert(1, new JobReference("b", null)), null)))
                .isInstanceOf(JobExceptions.JobConflictException.class);
    }

    @Test
    void submit_concurrentCreationWithEquivalentContentReturnsTheOtherJob() {
        JobRequest request = deleteRequest("a", "b");
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.concurrentlyCreated = InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null);

        JobModel.Job job = serviceResolvingTargets().submit(new JobModel.JobSubmission(jobId, request, null));

        assertThat(job.jobId()).isEqualTo(jobId);
        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.MATERIALIZED);
        assertThat(storage.tasksOf(jobId)).hasSize(2);
    }

    @Test
    void submit_concurrentCreationWithDifferentContentIsConflict() {
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.concurrentlyCreated = InMemoryJobStorage.job(jobId, deleteRequest("other"), JobState.OPEN,
                MaterializationState.MATERIALIZING, java.time.Instant.now(), null);
        JobSubmissionService service = serviceResolvingTargets();

        assertThatThrownBy(() -> service.submit(new JobModel.JobSubmission(jobId, deleteRequest("a", "b"), null)))
                .isInstanceOf(JobExceptions.JobConflictException.class);
    }

    @Test
    void materialize_permanentlyRejectedRefreshEndsTheJobAsFailed() {
        JobRequest request = upsert(1, new JobReference("a", null));
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null));
        storage.rejectRefresh = true;

        serviceWithLimit(10).materialize(storage.jobs.get(jobId));

        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.FAILED);
        assertThat(storage.jobs.get(jobId).result()).isEqualTo(JobResult.FAILED);
    }

    @Test
    void submit_retriesOfAJobStillBeingMaterializedQueueOnlyOneRun() {
        JobRequest request = upsert(1, new JobReference("a", null));
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null));
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        MaintenanceProperties properties = new MaintenanceProperties();
        JobSubmissionService service = new JobSubmissionService(properties, null, null, null, null, storage, storage,
                new JobClock(java.time.Clock.systemUTC()), queued::add, new JobReferenceCache());

        service.submit(new JobModel.JobSubmission(jobId, request, null));
        service.submit(new JobModel.JobSubmission(jobId, request, null));
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        service.submit(new JobModel.JobSubmission(jobId, request, null));
        assertThat(queued).as("a finished run does not block later ones").hasSize(1);
        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.MATERIALIZED);
    }

    @Test
    void submit_rejectedQueueingDoesNotBlockALaterRetry() {
        JobRequest request = upsert(1, new JobReference("a", null));
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null));
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        JobSubmissionService service = new JobSubmissionService(new MaintenanceProperties(), null, null, null, null, storage, storage,
                new JobClock(java.time.Clock.systemUTC()), task -> attempts.incrementAndGet() > 1 && InMemoryJobStorage.RUN_DIRECTLY.submit(task), new JobReferenceCache());

        service.submit(new JobModel.JobSubmission(jobId, request, null));
        service.submit(new JobModel.JobSubmission(jobId, request, null));

        assertThat(attempts).hasValue(2);
        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.MATERIALIZED);
    }

    @Test
    void materialize_jobWithoutItsReferencesIsRefused() {
        JobRequest request = upsert(1, new JobReference("a", null), new JobReference("b", null));
        java.util.UUID jobId = java.util.UUID.randomUUID();
        storage.store(InMemoryJobStorage.job(jobId, request, JobState.OPEN, MaterializationState.MATERIALIZING,
                java.time.Instant.now(), null));
        JobModel.Job withoutReferences = storage.find(jobId, false).orElseThrow();

        assertThatThrownBy(() -> serviceWithLimit(10).materialize(withoutReferences)).isInstanceOf(IllegalStateException.class);
        assertThat(storage.jobs.get(jobId).materializationState()).isEqualTo(MaterializationState.MATERIALIZING);
    }

    @Test
    void submit_seedsTheReferenceCacheSoThatTheFirstReportNeedsNoFullRead() {
        JobReferenceCache cache = new JobReferenceCache();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        JobSubmissionService service = serviceResolvingTargets(queued::add, cache);
        JobRequest request = deleteRequest("a", "b");

        JobModel.Job job = service.submit(new JobModel.JobSubmission(java.util.UUID.randomUUID(), request, null));

        assertThat(cache.get(job)).contains(request.references());
        assertThat(storage.fullReads).isZero();
    }

    private static JobRequest deleteRequest(String... ids) {
        return new JobRequest(JobType.DELETE, null, null, "DecreeDocument", 1,
                java.util.Arrays.stream(ids).map(id -> new JobReference(id, null)).toList());
    }

    // Delete jobs need no message configuration, only the index type and the target
    private JobSubmissionService serviceResolvingTargets() {
        return serviceResolvingTargets(InMemoryJobStorage.RUN_DIRECTLY, new JobReferenceCache());
    }

    private JobSubmissionService serviceResolvingTargets(MaterializationExecutor executor, JobReferenceCache cache) {
        IndexType<?> indexType = mock(IndexType.class);
        when(indexType.indexWriteAlias()).thenReturn("decree_document_v1_write");
        when(indexType.majorVersion()).thenReturn(1);
        IndexTypeRepository indexTypes = mock(IndexTypeRepository.class);
        doReturn(java.util.Optional.of(indexType)).when(indexTypes).findByOriginTypeAndMajorVersion("DecreeDocument", 1);
        JobStorage.JobTargetResolver resolver = alias -> new JobModel.ResolvedTarget("decree_document_v1-000001", "uuid");
        JobTargetAcceptanceCheck acceptanceCheck = mock(JobTargetAcceptanceCheck.class);
        when(acceptanceCheck.check("decree_document_v1-000001")).thenReturn(new JobTargetAcceptance("decree_document_v1-000001",
                PolicyCheckOutcome.NO_POLICY_APPLIED, null, "no policy"));
        return new JobSubmissionService(new MaintenanceProperties(), indexTypes, null, resolver, acceptanceCheck, storage, storage,
                new JobClock(java.time.Clock.systemUTC()), executor, cache);
    }

    private JobSubmissionService serviceWithLimit(int maxReferences) {
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setMaxReferences(maxReferences);
        return new JobSubmissionService(properties, null, null, null, null, storage, storage, new JobClock(java.time.Clock.systemUTC()), InMemoryJobStorage.RUN_DIRECTLY, new JobReferenceCache());
    }
}
