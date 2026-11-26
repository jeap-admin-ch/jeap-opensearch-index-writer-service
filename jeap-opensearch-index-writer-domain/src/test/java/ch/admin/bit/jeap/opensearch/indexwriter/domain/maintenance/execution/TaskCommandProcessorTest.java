package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskCommandProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private final InMemoryJobStorage storage = new InMemoryJobStorage();
    private final MaintenanceProperties properties = new MaintenanceProperties();
    private final UpsertTaskExecutor executor = mock(UpsertTaskExecutor.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TaskCommandProcessor processor = new TaskCommandProcessor(properties, storage, storage, executor,
            new JobClock(Clock.fixed(NOW, ZoneOffset.UTC)), meterRegistry);
    private Job job;

    @BeforeEach
    void setUp() {
        properties.setTaskMaxAttempts(3);
        job = InMemoryJobStorage.job(UUID.randomUUID(), InMemoryJobStorage.upsert("a"), JobState.OPEN,
                MaterializationState.MATERIALIZED, NOW.minusSeconds(60), null);
        storage.store(job);
        storage.storeTask(job, 0, TaskState.OPEN, null);
    }

    @Test
    void process_runsTheTaskAndSavesItsResult() {
        when(executor.execute(any(), any())).thenReturn(TaskResult.succeeded(1, 3));

        processor.process(command(task()));

        assertThat(task().state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(task().actualIndexMajorVersion()).isEqualTo(1);
        assertThat(task().actualIndexMinorVersion()).isEqualTo(3);
        assertThat(task().completedAt()).isEqualTo(NOW);
        assertThat(meterRegistry.get("jeap.opensearch.indexwriter.maintenance.task").tag("outcome", "succeeded").timer().count()).isEqualTo(1);
    }

    @Test
    void process_indexingDisabledIsAFinalState() {
        when(executor.execute(any(), any())).thenReturn(TaskResult.indexingDisabled());

        processor.process(command(task()));

        assertThat(task().state()).isEqualTo(TaskState.INDEXING_DISABLED);
        assertThat(task().error()).isNull();
    }

    @Test
    void process_neverRunsAFinishedTaskAgain() {
        when(executor.execute(any(), any())).thenReturn(TaskResult.succeeded(1, 0));
        processor.process(command(task()));

        processor.process(command(task()));

        verify(executor, times(1)).execute(any(), any());
    }

    @Test
    void process_ignoresCommandsOfUnknownJobs() {
        JobTask task = task();
        TaskCommand unknown = new TaskCommand(JobType.UPSERT, UUID.randomUUID(), task.taskId(), task.originId(),
                task.referenceVersion(), "Msg", "topic");

        processor.process(unknown);

        verifyNoInteractions(executor);
        assertThat(task().state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void process_ignoresCommandsThatDoNotMatchTheStoredTask() {
        JobTask task = task();

        processor.process(new TaskCommand(JobType.UPSERT, job.jobId(), task.taskId(), "other", task.referenceVersion(), "Msg", "topic"));
        processor.process(new TaskCommand(JobType.UPSERT, job.jobId(), task.taskId(), task.originId(), "v-other", "Msg", "topic"));
        processor.process(new TaskCommand(JobType.UPSERT, job.jobId(), task.taskId(), task.originId(), task.referenceVersion(), "Other", "topic"));
        processor.process(new TaskCommand(JobType.DELETE, job.jobId(), task.taskId(), task.originId(), null, null, null));

        verifyNoInteractions(executor);
    }

    @Test
    void process_transientErrorSchedulesARetryAndIgnoresEarlyCommands() {
        when(executor.execute(any(), any())).thenReturn(TaskResult.retry(TaskErrorCode.SOURCE_UNAVAILABLE, "HTTP 503", null));

        processor.process(command(task()));
        processor.process(command(task()));

        JobTask retried = task();
        assertThat(retried.state()).isEqualTo(TaskState.OPEN);
        assertThat(retried.attempts()).isEqualTo(1);
        assertThat(retried.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(retried.error().code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(meterRegistry.get("jeap.opensearch.indexwriter.maintenance.task").tag("outcome", "retry").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("jeap.opensearch.indexwriter.maintenance.task").tag("outcome", "failed").timer()).isNull();
        verify(executor, times(1)).execute(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void process_failsTheTaskAfterTheLastAttempt(int maxAttempts) {
        properties.setTaskMaxAttempts(maxAttempts);
        JobTask task = task();
        storage.storeTask(new JobTask(task.jobId(), task.taskId(), task.position(), task.originId(), task.referenceVersion(),
                TaskState.OPEN, null, null, null, null, task.createdAt(), null, maxAttempts - 1, NOW.minusSeconds(1), 5, 1));
        when(executor.execute(any(), any())).thenReturn(TaskResult.retry(TaskErrorCode.TARGET_UNAVAILABLE, "HTTP 429", "index_v1-000001"));

        processor.process(command(task()));

        JobTask failed = task();
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.nextAttemptAt()).isNull();
        assertThat(failed.error().code()).isEqualTo("TARGET_UNAVAILABLE");
        assertThat(failed.error().message()).isEqualTo("Gave up after %d attempts: HTTP 429".formatted(maxAttempts));
        assertThat(failed.error().targetIndex()).isEqualTo("index_v1-000001");
        assertThat(meterRegistry.get("jeap.opensearch.indexwriter.maintenance.task").tag("outcome", "failed").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find("jeap.opensearch.indexwriter.maintenance.task").tag("outcome", "retry").timer()).isNull();
    }

    @Test
    void process_redeliversWhenConcurrentDispatchClearsTheRetryWithoutSavingAnOutcome() {
        storage.scheduleRetry(task(), 1, NOW.minusSeconds(1),
                TaskResult.retry(TaskErrorCode.SOURCE_UNAVAILABLE, "HTTP 503", null).error());
        when(executor.execute(any(), any())).thenAnswer(_ -> {
            storage.clearScheduledRetries(List.of(task()));
            return TaskResult.succeeded(1, 0);
        }).thenReturn(TaskResult.succeeded(1, 0));
        TaskCommand command = command(task());

        assertThatThrownBy(() -> processor.process(command)).isInstanceOf(JobStorageUnavailableException.class)
                .hasMessageContaining("no durable outcome or scheduled retry");
        assertThat(task().state()).isEqualTo(TaskState.OPEN);
        assertThat(task().nextAttemptAt()).isNull();

        processor.process(command);

        assertThat(task().state()).isEqualTo(TaskState.SUCCEEDED);
        verify(executor, times(2)).execute(any(), any());
    }

    @Test
    void process_keepsTheOutcomeSavedByAConcurrentConsumer() {
        when(executor.execute(any(), any())).thenAnswer(_ -> {
            storage.complete(task(), TaskState.SUCCEEDED, 1, 2, null, NOW);
            return TaskResult.failed(TaskErrorCode.SOURCE_NOT_FOUND, "HTTP 404", null);
        });

        processor.process(command(task()));

        assertThat(task().state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(task().actualIndexMinorVersion()).isEqualTo(2);
        assertThat(task().error()).isNull();
    }

    @Test
    void process_keepsTheRetryScheduledByAConcurrentConsumer() {
        when(executor.execute(any(), any())).thenAnswer(_ -> {
            storage.scheduleRetry(task(), 1, NOW.plusSeconds(60),
                    TaskResult.retry(TaskErrorCode.SOURCE_UNAVAILABLE, "HTTP 503", null).error());
            return TaskResult.succeeded(1, 0);
        });

        processor.process(command(task()));

        assertThat(task().state()).isEqualTo(TaskState.OPEN);
        assertThat(task().attempts()).isEqualTo(1);
        assertThat(task().nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void process_doesNotRecreateATaskDeletedBeforeItsOutcomeWasSaved() {
        JobTask task = task();
        when(executor.execute(any(), any())).thenAnswer(_ -> {
            storage.tasks.remove(task.taskId());
            return TaskResult.succeeded(1, 0);
        });

        processor.process(command(task));

        assertThat(storage.find(task.taskId())).isEmpty();
    }

    @Test
    void process_unexpectedErrorFailsTheTaskInsteadOfKeepingTheJobOpen() {
        when(executor.execute(any(), any())).thenThrow(new IllegalStateException("defect"));

        processor.process(command(task()));

        assertThat(task().state()).isEqualTo(TaskState.FAILED);
        assertThat(task().error().code()).isEqualTo("UNEXPECTED_ERROR");
    }

    @Test
    void process_unavailableTaskStorageIsLeftToTheConsumerForRedelivery() {
        when(executor.execute(any(), any())).thenThrow(new JobStorageUnavailableException("down", null));

        assertThatThrownBy(() -> processor.process(command(task()))).isInstanceOf(JobStorageUnavailableException.class);
        assertThat(task().state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void backoff_doublesUpToTheMaximum() {
        Duration initial = Duration.ofMinutes(1);
        Duration max = Duration.ofMinutes(30);

        assertThat(TaskCommandProcessor.backoff(1, initial, max)).isEqualTo(Duration.ofMinutes(1));
        assertThat(TaskCommandProcessor.backoff(2, initial, max)).isEqualTo(Duration.ofMinutes(2));
        assertThat(TaskCommandProcessor.backoff(5, initial, max)).isEqualTo(Duration.ofMinutes(16));
        assertThat(TaskCommandProcessor.backoff(6, initial, max)).isEqualTo(Duration.ofMinutes(30));
        assertThat(TaskCommandProcessor.backoff(1000, initial, max)).isEqualTo(Duration.ofMinutes(30));
    }

    private JobTask task() {
        return storage.task(job, 0);
    }

    private TaskCommand command(JobTask task) {
        return new TaskCommand(JobType.UPSERT, job.jobId(), task.taskId(), task.originId(), task.referenceVersion(), "Msg", "topic");
    }
}
