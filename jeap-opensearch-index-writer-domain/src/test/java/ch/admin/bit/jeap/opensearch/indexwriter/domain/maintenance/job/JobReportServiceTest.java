package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobReport;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.ReportEntry;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.ReportSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final InMemoryJobStorage storage = new InMemoryJobStorage();
    private final MaintenanceProperties properties = new MaintenanceProperties();
    private final JobReportService service = new JobReportService(properties, storage, storage, new JobClock(Clock.fixed(NOW, ZoneOffset.UTC)), new JobReferenceCache());

    @Test
    void report_fillsPositionsWithoutTaskFromTheStoredRequest() {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZING);
        storage.storeTask(job, 0, TaskState.SUCCEEDED, NOW);

        JobReport report = service.report(job.jobId(), JobType.UPSERT, null, null);

        assertThat(report.entries()).extracting(ReportEntry::originId, ReportEntry::state, ReportEntry::version)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", TaskState.SUCCEEDED, "v-a"),
                        org.assertj.core.groups.Tuple.tuple("b", TaskState.OPEN, "v-b"),
                        org.assertj.core.groups.Tuple.tuple("c", TaskState.OPEN, "v-c"));
        assertThat(report.entries().get(1).taskId()).isEqualTo(JobModel.taskId(job.jobId(), "b"));
        assertThat(report.summary()).isEqualTo(new ReportSummary(3, 1, 2, 1, 0, 0));
        assertThat(report.nextCursor()).isNull();
        assertThat(report.reportCreated()).isEqualTo(NOW);
    }

    @Test
    void report_readsTheReferencesOnceWhilePollingAJobBeingMaterialized() {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZING);

        service.report(job.jobId(), JobType.UPSERT, null, 1);
        service.report(job.jobId(), JobType.UPSERT, "1", 1);
        service.report(job.jobId(), JobType.UPSERT, "2", 1);

        assertThat(storage.fullReads).isEqualTo(1);
    }

    @Test
    void report_doesNotServeTheCachedReferencesOfADeletedJobWithTheSameId() {
        Job old = store(upsert("a"), MaterializationState.MATERIALIZING);
        service.report(old.jobId(), JobType.UPSERT, null, null);
        storage.delete(old.jobId());
        Job reused = job(old.jobId(), upsert("x", "y"), JobState.OPEN, MaterializationState.MATERIALIZING, NOW, null);
        storage.store(reused);

        JobReport report = service.report(reused.jobId(), JobType.UPSERT, null, null);

        assertThat(report.entries()).extracting(ReportEntry::originId).containsExactly("x", "y");
    }

    @Test
    void report_jobWithRejectedTaskCreationHasNoOpenReferences() {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZING);
        storage.storeTask(job, 0, TaskState.SUCCEEDED, NOW);
        storage.storeTask(job, 1, TaskState.OPEN, null);
        storage.failMaterialization(storage.jobs.get(job.jobId()), NOW);

        JobReport report = service.report(job.jobId(), JobType.UPSERT, null, null);

        assertThat(report.entries()).extracting(ReportEntry::state)
                .containsExactly(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.FAILED);
        assertThat(report.entries().get(2).error()).isEqualTo(JobReportService.CREATION_REJECTED);
        assertThat(report.summary()).isEqualTo(new ReportSummary(3, 2, 0, 1, 2, 0));
    }

    @Test
    void report_pagesWithCursor() {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZED);
        for (int position = 0; position < 3; position++) {
            storage.storeTask(job, position, position == 2 ? TaskState.FAILED : TaskState.OPEN, null);
        }

        JobReport first = service.report(job.jobId(), JobType.UPSERT, null, 2);
        JobReport second = service.report(job.jobId(), JobType.UPSERT, first.nextCursor(), 2);

        assertThat(first.entries()).extracting(ReportEntry::originId).containsExactly("a", "b");
        assertThat(first.nextCursor()).isEqualTo("2");
        assertThat(second.entries()).extracting(ReportEntry::originId).containsExactly("c");
        assertThat(second.nextCursor()).isNull();
        assertThat(second.summary()).isEqualTo(new ReportSummary(3, 3, 2, 0, 1, 0));
    }

    @Test
    void report_deleteJobEntriesHaveNoVersion() {
        Job job = store(delete("a"), MaterializationState.MATERIALIZING);

        JobReport report = service.report(job.jobId(), JobType.DELETE, null, null);

        assertThat(report.entries()).singleElement().extracting(ReportEntry::version).isNull();
    }

    @Test
    void report_jobOfOtherTypeOrUnknownIsNotFound() {
        Job job = store(upsert("a"), MaterializationState.MATERIALIZED);

        assertThatThrownBy(() -> service.report(job.jobId(), JobType.DELETE, null, null)).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> service.report(UUID.randomUUID(), JobType.UPSERT, null, null)).isInstanceOf(JobNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "-1", "4"})
    void report_rejectsInvalidCursor(String cursor) {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZED);

        assertThatThrownBy(() -> service.report(job.jobId(), JobType.UPSERT, cursor, null))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("cursor");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1001})
    void report_rejectsInvalidLimit(int limit) {
        Job job = store(upsert("a"), MaterializationState.MATERIALIZED);

        assertThatThrownBy(() -> service.report(job.jobId(), JobType.UPSERT, null, limit))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("limit");
    }

    @Test
    void report_countsIndexingDisabledTasksAndShowsTheAttemptsOfRetriedTasks() {
        Job job = store(upsert("a", "b", "c"), MaterializationState.MATERIALIZED);
        storage.storeTask(job, 0, TaskState.INDEXING_DISABLED, NOW);
        storage.storeTask(job, 1, TaskState.SUCCEEDED, NOW);
        JobModel.JobTask open = JobModel.JobTask.open(job.jobId(), 2, "c", "v-c", NOW);
        JobModel.TaskError lastError = new JobModel.TaskError("SOURCE_UNAVAILABLE", "HTTP 503", null, null);
        storage.storeTask(new JobModel.JobTask(open.jobId(), open.taskId(), 2, "c", "v-c", TaskState.OPEN, null, null, null,
                lastError, NOW, null, 2, NOW.plusSeconds(120), 0, 1));

        JobReport report = service.report(job.jobId(), JobType.UPSERT, null, null);

        assertThat(report.summary()).isEqualTo(new ReportSummary(3, 3, 1, 1, 0, 1));
        assertThat(report.entries().get(0).state()).isEqualTo(TaskState.INDEXING_DISABLED);
        assertThat(report.entries().get(0).attempts()).isNull();
        assertThat(report.entries().get(2).attempts()).isEqualTo(2);
        assertThat(report.entries().get(2).error()).isEqualTo(lastError);
    }

    private Job store(JobModel.JobRequest request, MaterializationState materialization) {
        Job job = job(UUID.randomUUID(), request, JobState.OPEN, materialization, NOW.minusSeconds(60), null);
        storage.store(job);
        return job;
    }
}
