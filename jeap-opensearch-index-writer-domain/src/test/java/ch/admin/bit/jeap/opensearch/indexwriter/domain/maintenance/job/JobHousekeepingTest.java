package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.JobDispatcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage.job;
import static ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage.upsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JobHousekeepingTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final InMemoryJobStorage storage = new InMemoryJobStorage();
    private final MaintenanceProperties properties = new MaintenanceProperties();
    private final JobClock clock = new JobClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final JobSubmissionService submissionService =
            new JobSubmissionService(properties, null, null, null, null, storage, storage, clock, InMemoryJobStorage.RUN_DIRECTLY, new JobReferenceCache(), mock(JobDispatcher.class));
    private final JobHousekeeping housekeeping = new JobHousekeeping(properties, submissionService, storage, storage, mock(JobDispatcher.class), clock);

    @Test
    void completeFinishedJobs_derivesResultFromTaskStates() {
        Job succeeded = materialized(upsert("a", "b"));
        storage.storeTask(succeeded, 0, TaskState.SUCCEEDED, NOW.minusSeconds(20));
        storage.storeTask(succeeded, 1, TaskState.SUCCEEDED, NOW.minusSeconds(10));
        Job partially = materialized(upsert("a", "b"));
        storage.storeTask(partially, 0, TaskState.SUCCEEDED, NOW.minusSeconds(10));
        storage.storeTask(partially, 1, TaskState.FAILED, NOW.minusSeconds(10));
        Job failed = materialized(upsert("a"));
        storage.storeTask(failed, 0, TaskState.FAILED, NOW.minusSeconds(10));

        housekeeping.completeFinishedJobs();

        assertCompleted(succeeded, JobResult.SUCCEEDED, NOW.minusSeconds(10));
        assertCompleted(partially, JobResult.PARTIALLY_SUCCEEDED, NOW.minusSeconds(10));
        assertCompleted(failed, JobResult.FAILED, NOW.minusSeconds(10));
    }

    @Test
    void completeFinishedJobs_keepsJobsWithOpenOrMissingTasksOpen() {
        Job withOpenTask = materialized(upsert("a", "b"));
        storage.storeTask(withOpenTask, 0, TaskState.SUCCEEDED, NOW);
        storage.storeTask(withOpenTask, 1, TaskState.OPEN, null);
        Job withMissingTask = materialized(upsert("a", "b"));
        storage.storeTask(withMissingTask, 0, TaskState.SUCCEEDED, NOW);

        housekeeping.completeFinishedJobs();

        assertThat(storage.jobs.get(withOpenTask.jobId()).state()).isEqualTo(JobState.OPEN);
        assertThat(storage.jobs.get(withMissingTask.jobId()).state()).isEqualTo(JobState.OPEN);
    }

    @Test
    void completeFinishedJobs_visitsCandidatesBeyondTheFirstPage() {
        List<Job> unfinished = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            Job job = materialized(upsert("a"), NOW.minus(Duration.ofHours(2)).plusSeconds(i));
            storage.storeTask(job, 0, TaskState.OPEN, null);
            unfinished.add(job);
        }
        List<Job> finished = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Job job = materialized(upsert("a"), NOW.minus(Duration.ofHours(1)).plusSeconds(i));
            storage.storeTask(job, 0, TaskState.SUCCEEDED, NOW);
            finished.add(job);
        }

        housekeeping.completeFinishedJobs();

        assertThat(finished).allSatisfy(j -> assertThat(storage.jobs.get(j.jobId()).state()).isEqualTo(JobState.COMPLETED));
        assertThat(unfinished).allSatisfy(j -> assertThat(storage.jobs.get(j.jobId()).state()).isEqualTo(JobState.OPEN));
    }

    @Test
    void resumeMaterialization_createsMissingTasksOfStaleJobsOnly() {
        Job stale = job(UUID.randomUUID(), upsert("a", "b", "c"), JobState.OPEN, MaterializationState.MATERIALIZING,
                NOW.minus(properties.getMaterializationResumeAfter()).minusSeconds(1), null);
        storage.store(stale);
        storage.storeTask(stale, 0, TaskState.SUCCEEDED, NOW);
        Job inProgress = job(UUID.randomUUID(), upsert("a"), JobState.OPEN, MaterializationState.MATERIALIZING,
                NOW.minusSeconds(5), null);
        storage.store(inProgress);

        housekeeping.resumeMaterialization();

        assertThat(storage.jobs.get(stale.jobId()).materializationState()).isEqualTo(MaterializationState.MATERIALIZED);
        assertThat(storage.tasksOf(stale.jobId())).extracting(JobModel.JobTask::originId, JobModel.JobTask::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", TaskState.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple("b", TaskState.OPEN),
                        org.assertj.core.groups.Tuple.tuple("c", TaskState.OPEN));
        assertThat(storage.jobs.get(inProgress.jobId()).materializationState()).isEqualTo(MaterializationState.MATERIALIZING);
        assertThat(storage.tasksOf(inProgress.jobId())).isEmpty();
    }

    @Test
    void resumeMaterialization_endsAJobWhoseTasksAreRejected_andDoesNotRetryIt() {
        Job rejected = job(UUID.randomUUID(), upsert("a"), JobState.OPEN, MaterializationState.MATERIALIZING,
                NOW.minus(properties.getMaterializationResumeAfter()).minusSeconds(1), null);
        storage.store(rejected);
        storage.rejectedTaskCreations.add(rejected.jobId());

        housekeeping.resumeMaterialization();
        housekeeping.resumeMaterialization();

        Job stored = storage.jobs.get(rejected.jobId());
        assertThat(stored.state()).isEqualTo(JobState.COMPLETED);
        assertThat(stored.result()).isEqualTo(JobResult.FAILED);
        assertThat(stored.materializationState()).isEqualTo(MaterializationState.FAILED);
        assertThat(stored.completedAt()).isEqualTo(NOW);
        assertThat(storage.fullReads).as("the failed job is not picked up again").isEqualTo(1);
    }

    @Test
    void deleteExpiredJobs_deletesCompletedJobsAfterRetentionOnly() {
        Job expired = completed(NOW.minus(properties.getRetention()).minusSeconds(1));
        Job recent = completed(NOW.minus(properties.getRetention()).plusSeconds(60));
        Job oldButOpen = materialized(upsert("a"), NOW.minus(Duration.ofDays(365)));
        storage.storeTask(oldButOpen, 0, TaskState.OPEN, null);

        housekeeping.deleteExpiredJobs();

        assertThat(storage.jobs).doesNotContainKey(expired.jobId()).containsKeys(recent.jobId(), oldButOpen.jobId());
        assertThat(storage.tasksOf(expired.jobId())).isEmpty();
        assertThat(storage.tasksOf(recent.jobId())).hasSize(1);
    }

    @Test
    void deleteExpiredJobs_keepsJobIfItsTasksCannotBeDeleted_andContinuesWithOthers() {
        Job failing = completed(NOW.minus(Duration.ofDays(40)));
        Job other = completed(NOW.minus(Duration.ofDays(40)));
        storage.failingTaskDeletes.add(failing.jobId());

        housekeeping.deleteExpiredJobs();

        assertThat(storage.jobs).containsKey(failing.jobId()).doesNotContainKey(other.jobId());
        assertThat(storage.tasksOf(failing.jobId())).hasSize(1);
    }

    @Test
    void completeFinishedJobs_countsIndexingDisabledTasksAsNotSucceeded() {
        Job partially = materialized(upsert("a", "b"));
        storage.storeTask(partially, 0, TaskState.SUCCEEDED, NOW.minusSeconds(10));
        storage.storeTask(partially, 1, TaskState.INDEXING_DISABLED, NOW.minusSeconds(10));
        Job allDisabled = materialized(upsert("a"));
        storage.storeTask(allDisabled, 0, TaskState.INDEXING_DISABLED, NOW.minusSeconds(10));
        Job failedAndDisabled = materialized(upsert("a", "b"));
        storage.storeTask(failedAndDisabled, 0, TaskState.FAILED, NOW.minusSeconds(10));
        storage.storeTask(failedAndDisabled, 1, TaskState.INDEXING_DISABLED, NOW.minusSeconds(10));

        housekeeping.completeFinishedJobs();

        assertCompleted(partially, JobResult.PARTIALLY_SUCCEEDED, NOW.minusSeconds(10));
        assertCompleted(allDisabled, JobResult.FAILED, NOW.minusSeconds(10));
        assertCompleted(failedAndDisabled, JobResult.FAILED, NOW.minusSeconds(10));
    }

    private Job materialized(JobModel.JobRequest request) {
        return materialized(request, NOW.minus(Duration.ofHours(1)));
    }

    private Job materialized(JobModel.JobRequest request, Instant startedAt) {
        Job job = job(UUID.randomUUID(), request, JobState.OPEN, MaterializationState.MATERIALIZED, startedAt, null);
        storage.store(job);
        return job;
    }

    private Job completed(Instant completedAt) {
        Job job = job(UUID.randomUUID(), upsert("a"), JobState.COMPLETED, MaterializationState.MATERIALIZED,
                completedAt.minus(Duration.ofHours(1)), completedAt);
        storage.store(job);
        storage.storeTask(job, 0, TaskState.SUCCEEDED, completedAt);
        return job;
    }

    private void assertCompleted(Job job, JobResult result, Instant completedAt) {
        Job stored = storage.jobs.get(job.jobId());
        assertThat(stored.state()).isEqualTo(JobState.COMPLETED);
        assertThat(stored.result()).isEqualTo(result);
        assertThat(stored.completedAt()).isEqualTo(completedAt);
    }
}
