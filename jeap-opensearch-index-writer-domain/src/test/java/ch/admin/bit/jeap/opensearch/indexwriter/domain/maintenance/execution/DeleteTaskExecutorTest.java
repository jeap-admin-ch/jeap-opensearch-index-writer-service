package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Completed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Retry;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.DeletionOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTarget;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TargetOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MajorSelection;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.*;

class DeleteTaskExecutorTest {

    private static final String V1 = "decree_document_v1-000001";
    private static final String V2 = "decree_document_v2-000001";

    private final MaintenanceTargetWriter writer = mock(MaintenanceTargetWriter.class);
    private final DeleteTaskExecutor executor = new DeleteTaskExecutor(writer);
    private final Job job = InMemoryJobStorage.withTargets(InMemoryJobStorage.job(UUID.randomUUID(), InMemoryJobStorage.delete("12345"),
            JobState.OPEN, MaterializationState.MATERIALIZED, Instant.now(), null), List.of(target(1, V1), target(2, V2)));
    private final JobTask task = JobTask.open(job.jobId(), 0, "12345", null, Instant.now());

    @Test
    void execute_succeedsIfEveryTargetIsDeletedOrAlreadyAbsent() {
        when(writer.delete(V1, "12345")).thenReturn(DeletionOutcome.DELETED);
        when(writer.delete(V2, "12345")).thenReturn(DeletionOutcome.ALREADY_ABSENT);

        Completed result = (Completed) executor.execute(job, task);

        assertThat(result.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(result.targetOutcomes()).extracting(TargetOutcome::indexMajorVersion, TargetOutcome::targetIndex, TargetOutcome::outcome)
                .containsExactly(tuple(1, V1, DeletionOutcome.DELETED), tuple(2, V2, DeletionOutcome.ALREADY_ABSENT));
    }

    @Test
    void execute_failsTheTaskButKeepsEveryOutcomeIfOneTargetRefuses() {
        when(writer.delete(V1, "12345")).thenReturn(DeletionOutcome.DELETED);
        when(writer.delete(V2, "12345")).thenThrow(TargetWriteException.permanent(TaskErrorCode.INDEX_NOT_WRITABLE, "write block", null));

        Completed result = (Completed) executor.execute(job, task);

        assertThat(result.state()).isEqualTo(TaskState.FAILED);
        assertThat(result.targetOutcomes().get(0).outcome()).isEqualTo(DeletionOutcome.DELETED);
        TargetOutcome refused = result.targetOutcomes().get(1);
        assertThat(refused.outcome()).isEqualTo(DeletionOutcome.FAILED);
        assertThat(refused.error().code()).isEqualTo("INDEX_NOT_WRITABLE");
        assertThat(refused.error().targetIndex()).isEqualTo(V2);
    }

    @Test
    void execute_unexpectedErrorOnOneTargetFailsOnlyThatTargetAndStillProcessesTheOthers() {
        when(writer.delete(V1, "12345")).thenThrow(new IllegalStateException("defect"));
        when(writer.delete(V2, "12345")).thenReturn(DeletionOutcome.DELETED);

        Completed result = (Completed) executor.execute(job, task);

        assertThat(result.state()).isEqualTo(TaskState.FAILED);
        TargetOutcome failed = result.targetOutcomes().get(0);
        assertThat(failed.outcome()).isEqualTo(DeletionOutcome.FAILED);
        assertThat(failed.error().code()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(failed.error().targetIndex()).isEqualTo(V1);
        assertThat(result.targetOutcomes().get(1).outcome()).isEqualTo(DeletionOutcome.DELETED);
    }

    @Test
    void execute_retriesOnlyTheTargetsWithoutAFinalOutcome() {
        when(writer.delete(V1, "12345")).thenReturn(DeletionOutcome.DELETED);
        when(writer.delete(V2, "12345")).thenThrow(TargetWriteException.unavailable("HTTP 503", null)).thenReturn(DeletionOutcome.DELETED);

        Retry first = (Retry) executor.execute(job, task);
        assertThat(first.error().code()).isEqualTo("TARGET_UNAVAILABLE");
        assertThat(first.targetOutcomes()).extracting(TargetOutcome::outcome).containsExactly(DeletionOutcome.DELETED, null);

        Completed second = (Completed) executor.execute(job, withOutcomes(first.targetOutcomes()));

        assertThat(second.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(second.targetOutcomes()).extracting(TargetOutcome::outcome).containsExactly(DeletionOutcome.DELETED, DeletionOutcome.DELETED);
        verify(writer, times(1)).delete(V1, "12345");
        verify(writer, times(2)).delete(V2, "12345");
    }

    @Test
    void exhaustedRetry_failsTheTargetsStillWithoutOutcome() {
        when(writer.delete(V1, "12345")).thenReturn(DeletionOutcome.DELETED);
        when(writer.delete(V2, "12345")).thenThrow(TargetWriteException.unavailable("HTTP 503", null));

        Completed result = ((Retry) executor.execute(job, task)).exhausted(6);

        assertThat(result.state()).isEqualTo(TaskState.FAILED);
        assertThat(result.targetOutcomes().get(0).outcome()).isEqualTo(DeletionOutcome.DELETED);
        assertThat(result.targetOutcomes().get(1).outcome()).isEqualTo(DeletionOutcome.FAILED);
        assertThat(result.targetOutcomes().get(1).error().message()).isEqualTo("Gave up after 6 attempts: HTTP 503");
    }

    private JobTask withOutcomes(List<TargetOutcome> outcomes) {
        return new JobTask(task.jobId(), task.taskId(), 0, "12345", null, TaskState.OPEN, null, null, outcomes, null,
                task.createdAt(), null, 1, null, 1, 1);
    }

    private static JobTarget target(int major, String index) {
        return new JobTarget(MajorSelection.ALL, major, index, "uuid-" + major, JobTarget.ROLLOVER_NOT_DETECTED);
    }
}
