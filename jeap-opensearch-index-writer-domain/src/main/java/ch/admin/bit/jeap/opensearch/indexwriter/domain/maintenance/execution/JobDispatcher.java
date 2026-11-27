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
    private static final String STUCK_METRICS_NAME = "jeap.opensearch.indexwriter.maintenance.jobs.stuck";

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
            withLease(job, UnaryOperator.identity(), this::sendRemaining);
        } else if (!taskRepository.findDueRetries(job.jobId(), now, -1, 1).isEmpty()) {
            withLease(job, UnaryOperator.identity(),
                    current -> resend(current, "retry", (after, limit) -> taskRepository.findDueRetries(current.jobId(), now, after, limit), true));
        } else if (job.dispatch().stuckAt() == null && isStalled(job, now)) {
            redispatchOrMarkStuck(job, now);
        }
    }

    // A stall cannot tell lost commands from commands still queued behind other jobs, so the rounds are capped: a job
    // that stalls again after the last round is only reported, its open tasks still finish if their commands arrive
    private void redispatchOrMarkStuck(Job job, Instant now) {
        int rounds = job.dispatch().redispatches();
        if (rounds >= properties.getTaskMaxRedispatches()) {
            if (jobRepository.updateDispatch(job, job.dispatch().stuck(now)).isPresent()) {
                log.warn("Job {} made no progress for {} after {} rounds of sending its unfinished tasks again; nothing more is sent, the job is reported as stuck",
                        job.jobId(), properties.getTaskRedispatchAfter(), rounds);
                meterRegistry.counter(STUCK_METRICS_NAME, "job_type", Wire.of(job.type())).increment();
            }
            return;
        }
        log.warn("Job {} made no progress for {}, the commands of its unfinished tasks are sent again (round {} of {})", job.jobId(),
                properties.getTaskRedispatchAfter(), rounds + 1, properties.getTaskMaxRedispatches());
        withLease(job, Dispatch::redispatched,
                current -> resend(current, "redispatch", (after, limit) -> taskRepository.findOpenUnscheduled(current.jobId(), after, limit), false));
    }

    // Only upsert jobs are run so far; delete jobs are stored and reported, but not executed yet
    private static boolean isExecutable(Job job) {
        return job.type() == JobType.UPSERT && job.state() == JobState.OPEN
               && job.materializationState() == MaterializationState.MATERIALIZED;
    }

    // change is stored together with the lease, so that it is recorded only if this instance gets to do the work
    private void withLease(Job job, UnaryOperator<Dispatch> change, UnaryOperator<Job> work) {
        Optional<Job> leased = jobRepository.updateDispatch(job, change.apply(job.dispatch()).leased(instance, leaseUntil()));
        if (leased.isEmpty()) {
            return;
        }
        Job done = work.apply(leased.get());
        if (done != null) {
            jobRepository.updateDispatch(done, done.dispatch().released());
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
            Optional<Job> advanced = jobRepository.updateDispatch(current, current.dispatch().sent(to, leaseUntil(), clock.now()));
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
                    current.dispatch().sent(current.dispatch().cursor(), leaseUntil(), clock.now()));
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
