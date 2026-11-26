package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Dispatch;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskCounts;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.Wire;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

// Sends one command per task. Safe to call repeatedly and on several instances: a lease on the job lets one instance
// send its commands, and the stored cursor means a crash sends at most one batch twice. Commands sent twice are
// harmless, a finished task is never run again.
@Component
@ConditionalOnMaintenanceEnabled
@Slf4j
public class JobDispatcher {

    private static final String COMMANDS_METRICS_NAME = "jeap.opensearch.indexwriter.maintenance.commands";

    private final MaintenanceProperties properties;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final TaskCommandPublisher publisher;
    private final JobClock clock;
    private final MeterRegistry meterRegistry;
    private final String instance = UUID.randomUUID().toString();

    public JobDispatcher(MaintenanceProperties properties, JobRepository jobRepository, TaskRepository taskRepository,
                         TaskCommandPublisher publisher, JobClock clock, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public void dispatch(Job job) {
        if (!isExecutable(job)) {
            return;
        }
        Instant now = clock.now();
        if (job.dispatch().isLeasedByOtherAt(instance, now)) {
            return;
        }
        if (!job.isFullyDispatched()) {
            withLease(job, this::sendRemaining);
        } else if (!taskRepository.findDueRetries(job.jobId(), now, -1, 1).isEmpty()) {
            withLease(job, current -> resend(current, "retry", (after, limit) -> taskRepository.findDueRetries(current.jobId(), now, after, limit), true));
        } else if (isStalled(job, now)) {
            log.warn("Job {} made no progress for {}, the commands of its unfinished tasks are sent again", job.jobId(),
                    properties.getTaskRedispatchAfter());
            withLease(job, current -> resend(current, "redispatch", (after, limit) -> taskRepository.findOpenUnscheduled(current.jobId(), after, limit), false));
        }
    }

    // Delete jobs are run from JEAP-7797 on
    private static boolean isExecutable(Job job) {
        return job.type() == JobType.UPSERT && job.state() == JobState.OPEN
               && job.materializationState() == MaterializationState.MATERIALIZED;
    }

    private void withLease(Job job, UnaryOperator<Job> work) {
        Dispatch dispatch = job.dispatch();
        Optional<Job> leased = jobRepository.updateDispatch(job, new Dispatch(dispatch.cursor(), instance, leaseUntil(), dispatch.dispatchedAt()));
        if (leased.isEmpty()) {
            return;
        }
        Job done = work.apply(leased.get());
        if (done != null) {
            jobRepository.updateDispatch(done, new Dispatch(done.dispatch().cursor(), null, null, done.dispatch().dispatchedAt()));
        }
    }

    // Returns null if the lease was lost
    private Job sendRemaining(Job job) {
        Job current = job;
        while (!current.isFullyDispatched()) {
            int from = current.dispatch().cursor();
            int to = Math.min(from + properties.getDispatchBatchSize(), current.expectedTaskCount());
            // Tasks waiting for a retry are sent by the retry step once due
            List<JobTask> open = taskRepository.findByPositionRange(current.jobId(), from, to).stream()
                    .filter(t -> t.state() == TaskState.OPEN && t.nextAttemptAt() == null)
                    .toList();
            send(current, open, "initial");
            Optional<Job> advanced = jobRepository.updateDispatch(current, new Dispatch(to, instance, leaseUntil(), clock.now()));
            if (advanced.isEmpty()) {
                log.info("Lost the dispatch lease of job {} at position {}, another instance continues", current.jobId(), to);
                return null;
            }
            current = advanced.get();
        }
        log.info("Sent the commands of all {} tasks of job {}", current.expectedTaskCount(), current.jobId());
        return current;
    }

    // Returns null if the lease was lost
    private Job resend(Job job, String kind, BiFunction<Integer, Integer, List<JobTask>> page, boolean clearScheduledRetries) {
        int batch = properties.getDispatchBatchSize();
        Job current = job;
        int after = -1;
        List<JobTask> tasks;
        do {
            tasks = page.apply(after, batch);
            if (tasks.isEmpty()) {
                break;
            }
            if (clearScheduledRetries) {
                taskRepository.clearScheduledRetries(tasks);
            }
            send(current, tasks, kind);
            after = tasks.getLast().position();
            Optional<Job> renewed = jobRepository.updateDispatch(current,
                    new Dispatch(current.dispatch().cursor(), instance, leaseUntil(), clock.now()));
            if (renewed.isEmpty()) {
                return null;
            }
            current = renewed.get();
        } while (tasks.size() == batch);
        return current;
    }

    private void send(Job job, List<JobTask> tasks, String kind) {
        if (tasks.isEmpty()) {
            return;
        }
        publisher.publish(job, tasks);
        meterRegistry.counter(COMMANDS_METRICS_NAME, "job_type", Wire.of(job.type()), "kind", kind).increment(tasks.size());
    }

    // Progress means a task finished or commands were sent; open tasks waiting for a retry are handled by the retry step
    private boolean isStalled(Job job, Instant now) {
        Instant threshold = now.minus(properties.getTaskRedispatchAfter());
        Instant dispatchedAt = job.dispatch().dispatchedAt();
        if (dispatchedAt != null && dispatchedAt.isAfter(threshold)) {
            return false;
        }
        TaskCounts counts = taskRepository.counts(job.jobId());
        if (counts.lastCompletedAt() != null && counts.lastCompletedAt().isAfter(threshold)) {
            return false;
        }
        return !taskRepository.findOpenUnscheduled(job.jobId(), -1, 1).isEmpty();
    }

    private Instant leaseUntil() {
        return clock.now().plus(properties.getDispatchLease());
    }
}
