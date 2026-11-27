package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Dispatch;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobRequest;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private final InMemoryJobStorage storage = new InMemoryJobStorage();
    private final MaintenanceProperties properties = new MaintenanceProperties();
    private final List<List<String>> sent = new ArrayList<>();
    private int failAtBatch = -1;
    private final TaskCommandPublisher publisher = (job, tasks) -> {
        if (sent.size() == failAtBatch) {
            throw new TaskDispatchException("broker down", null);
        }
        sent.add(tasks.stream().map(JobTask::originId).toList());
    };
    private final JobDispatcher dispatcher = new JobDispatcher(properties, storage, storage, publisher,
            new JobClock(Clock.fixed(NOW, ZoneOffset.UTC)), new SimpleMeterRegistry());

    @BeforeEach
    void setUp() {
        properties.setDispatchBatchSize(2);
    }

    @Test
    void dispatch_sendsTheOpenTasksInBatchesAndStoresTheProgress() {
        Job job = materialized(InMemoryJobStorage.upsert("a", "b", "c", "d", "e"), Dispatch.NONE);
        storage.storeTask(job, 3, TaskState.SUCCEEDED, NOW);

        dispatcher.dispatch(job);

        assertThat(sent).containsExactly(List.of("a", "b"), List.of("c"), List.of("e"));
        Dispatch dispatch = stored(job).dispatch();
        assertThat(dispatch.cursor()).isEqualTo(5);
        assertThat(dispatch.owner()).as("the lease is released").isNull();
        assertThat(dispatch.dispatchedAt()).isEqualTo(NOW);
    }

    @Test
    void dispatch_leavesAJobAloneWhileAnotherInstanceHoldsItsLease() {
        Job job = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(0, "other", NOW.plusSeconds(60), null, 0, null));

        dispatcher.dispatch(job);

        assertThat(sent).isEmpty();
    }

    @Test
    void dispatch_takesOverAnExpiredLeaseAtTheStoredCursor() {
        Job job = materialized(InMemoryJobStorage.upsert("a", "b", "c"), new Dispatch(2, "crashed", NOW.minusSeconds(1), NOW.minusSeconds(200), 0, null));

        dispatcher.dispatch(job);

        assertThat(sent).containsExactly(List.of("c"));
        assertThat(stored(job).dispatch().cursor()).isEqualTo(3);
    }

    @Test
    void dispatch_keepsTheProgressOfTheBatchesSentBeforeAFailure() {
        Job job = materialized(InMemoryJobStorage.upsert("a", "b", "c"), Dispatch.NONE);
        failAtBatch = 1;

        assertThatThrownBy(() -> dispatcher.dispatch(job)).isInstanceOf(TaskDispatchException.class);

        assertThat(stored(job).dispatch().cursor()).isEqualTo(2);
    }

    @Test
    void dispatch_sendsDeleteJobsButNoJobWhoseTasksAreNotAllCreated() {
        Job delete = materialized(InMemoryJobStorage.delete("a"), Dispatch.NONE);
        Job materializing = InMemoryJobStorage.job(UUID.randomUUID(), InMemoryJobStorage.upsert("b"), JobState.OPEN,
                MaterializationState.MATERIALIZING, NOW, null);
        storage.store(materializing);

        dispatcher.dispatch(delete);
        dispatcher.dispatch(materializing);

        assertThat(sent).containsExactly(List.of("a"));
    }

    @Test
    void dispatch_sendsDueRetriesAgainAndClearsTheirSchedule() {
        Job job = materialized(InMemoryJobStorage.upsert("a", "b", "c"), new Dispatch(3, null, null, NOW.minusSeconds(10), 0, null));
        scheduleRetry(job, 0, NOW.minusSeconds(1));
        scheduleRetry(job, 1, NOW.plusSeconds(60));

        dispatcher.dispatch(job);

        assertThat(sent).containsExactly(List.of("a"));
        assertThat(storage.task(job, 0).nextAttemptAt()).isNull();
        assertThat(storage.task(job, 1).nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void dispatch_doesNotInvalidateTheTaskVersionReadByTheRetryConsumer() {
        Job job = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(1, null, null, NOW.minusSeconds(10), 0, null));
        scheduleRetry(job, 0, NOW.minusSeconds(1));
        List<JobTask> readByConsumer = new ArrayList<>();
        JobDispatcher concurrentDispatcher = new JobDispatcher(properties, storage, storage,
                (dispatchedJob, tasks) -> readByConsumer.add(storage.task(dispatchedJob, 0)),
                new JobClock(Clock.fixed(NOW, ZoneOffset.UTC)), new SimpleMeterRegistry());

        concurrentDispatcher.dispatch(job);

        assertThat(readByConsumer).hasSize(1);
        JobTask task = readByConsumer.getFirst();
        assertThat(task.nextAttemptAt()).isNull();
        assertThat(storage.complete(task, TaskState.SUCCEEDED, 1, 0, List.of(), null, NOW))
                .as("the consumer can persist its result after dispatch has finished").isTrue();
        assertThat(storage.task(job, 0).state()).isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    void dispatch_recoversARetryWhoseScheduleWasClearedBeforePublicationFailed() {
        Job job = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(1, null, null, NOW.minusSeconds(10), 0, null));
        scheduleRetry(job, 0, NOW.minusSeconds(1));
        failAtBatch = 0;

        assertThatThrownBy(() -> dispatcher.dispatch(job)).isInstanceOf(TaskDispatchException.class);
        assertThat(storage.task(job, 0).nextAttemptAt()).isNull();
        assertThat(storage.task(job, 0).attempts()).isEqualTo(1);

        failAtBatch = -1;
        Instant later = NOW.plus(properties.getTaskRedispatchAfter()).plus(properties.getDispatchLease());
        JobDispatcher recoveringDispatcher = new JobDispatcher(properties, storage, storage, publisher,
                new JobClock(Clock.fixed(later, ZoneOffset.UTC)), new SimpleMeterRegistry());
        recoveringDispatcher.dispatch(stored(job));

        assertThat(sent).containsExactly(List.of("a"));
        assertThat(storage.task(job, 0).attempts()).isEqualTo(1);
    }

    @Test
    void dispatch_sendsTheUnfinishedTasksOfAStalledJobAgain() {
        properties.setTaskRedispatchAfter(Duration.ofMinutes(30));
        Job job = materialized(InMemoryJobStorage.upsert("a", "b", "c"), new Dispatch(3, null, null, NOW.minus(Duration.ofMinutes(31)), 0, null));
        storage.storeTask(job, 1, TaskState.SUCCEEDED, NOW.minus(Duration.ofMinutes(40)));
        scheduleRetry(job, 2, NOW.plusSeconds(60));

        dispatcher.dispatch(job);

        assertThat(sent).as("the task waiting for its retry is left to the retry").containsExactly(List.of("a"));
        assertThat(stored(job).dispatch().dispatchedAt()).isEqualTo(NOW);
    }

    @Test
    void dispatch_doesNotSendAgainWhileAJobMakesProgress() {
        Job recentlySent = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(1, null, null, NOW.minus(Duration.ofMinutes(5)), 0, null));
        Job recentlyCompleted = materialized(InMemoryJobStorage.upsert("a", "b"), new Dispatch(2, null, null, NOW.minus(Duration.ofHours(2)), 0, null));
        storage.storeTask(recentlyCompleted, 1, TaskState.SUCCEEDED, NOW.minus(Duration.ofMinutes(5)));

        dispatcher.dispatch(recentlySent);
        dispatcher.dispatch(recentlyCompleted);

        assertThat(sent).isEmpty();
    }

    @Test
    void dispatch_countsTheRoundsOfSendingAStalledJobAgain() {
        properties.setTaskRedispatchAfter(Duration.ofMinutes(30));
        Job job = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(1, null, null, NOW.minus(Duration.ofHours(1)), 1, null));

        dispatcher.dispatch(job);

        assertThat(sent).containsExactly(List.of("a"));
        assertThat(stored(job).dispatch().redispatches()).isEqualTo(2);
        assertThat(stored(job).dispatch().owner()).isNull();
    }

    @Test
    void dispatch_reportsAJobAsStuckInsteadOfSendingItAgainAfterTheLastRound() {
        properties.setTaskRedispatchAfter(Duration.ofMinutes(30));
        properties.setTaskMaxRedispatches(3);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JobDispatcher capped = new JobDispatcher(properties, storage, storage, publisher,
                new JobClock(Clock.fixed(NOW, ZoneOffset.UTC)), meters);
        Job job = materialized(InMemoryJobStorage.upsert("a"), new Dispatch(1, null, null, NOW.minus(Duration.ofHours(1)), 3, null));

        capped.dispatch(job);
        capped.dispatch(stored(job));

        assertThat(sent).isEmpty();
        assertThat(stored(job).dispatch().stuckAt()).isEqualTo(NOW);
        assertThat(meters.get("jeap.opensearch.indexwriter.maintenance.jobs.stuck").counter().count()).as("reported once").isEqualTo(1);
        assertThat(storage.task(job, 0).state()).as("the task stays open and still finishes if its command arrives").isEqualTo(TaskState.OPEN);
    }

    @Test
    void dispatch_stillSendsDueRetriesOfAStuckJob() {
        Job job = materialized(InMemoryJobStorage.upsert("a", "b"), new Dispatch(2, null, null, NOW.minus(Duration.ofHours(3)), 3, NOW.minusSeconds(60)));
        scheduleRetry(job, 1, NOW.minusSeconds(1));

        dispatcher.dispatch(job);

        assertThat(sent).containsExactly(List.of("b"));
    }

    private Job materialized(JobRequest request, Dispatch dispatch) {
        Job job = InMemoryJobStorage.job(UUID.randomUUID(), request, JobState.OPEN, MaterializationState.MATERIALIZED,
                NOW.minus(Duration.ofHours(3)), null).withDispatch(dispatch, 0, 1);
        storage.store(job);
        for (int position = 0; position < request.references().size(); position++) {
            storage.storeTask(job, position, TaskState.OPEN, null);
        }
        return job;
    }

    private void scheduleRetry(Job job, int position, Instant nextAttemptAt) {
        JobTask task = storage.task(job, position);
        storage.storeTask(new JobTask(task.jobId(), task.taskId(), task.position(), task.originId(), task.referenceVersion(),
                TaskState.OPEN, null, null, null, null, task.createdAt(), null, 1, nextAttemptAt, task.seqNo(), task.primaryTerm()));
    }

    private Job stored(Job job) {
        return storage.jobs.get(job.jobId());
    }
}
