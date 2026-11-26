package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Completed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Retry;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskError;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.Wire;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

// The stored job and task are authoritative: a command only says which task to run, it never creates or changes a job.
// Storage failures reading the job/task or saving the result require command redelivery.
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
public class TaskCommandProcessor {

    private static final String TASK_METRICS_NAME = "jeap.opensearch.indexwriter.maintenance.task";

    private final MaintenanceProperties properties;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final UpsertTaskExecutor upsertExecutor;
    private final JobClock clock;
    private final MeterRegistry meterRegistry;

    public void process(TaskCommand command) {
        Optional<Job> job = jobRepository.find(command.jobId(), false);
        if (job.isEmpty() || job.get().type() != command.type()) {
            log.warn("Ignoring {} command for task {}: job {} does not exist (it may have been deleted after its retention) or has another type",
                    Wire.of(command.type()), command.taskId(), command.jobId());
            return;
        }
        Optional<JobTask> task = taskRepository.find(command.taskId());
        if (task.isEmpty()) {
            log.warn("Ignoring {} command for job {}: task {} does not exist", Wire.of(command.type()), command.jobId(), command.taskId());
            return;
        }
        String mismatch = mismatch(command, job.get(), task.get());
        if (mismatch != null) {
            log.warn("Ignoring {} command for task {} of job {}: {}", Wire.of(command.type()), command.taskId(), command.jobId(), mismatch);
            return;
        }
        JobTask current = task.get();
        if (current.state().isFinal()) {
            log.debug("Task {} of job {} is already {}, the command is not run again", current.taskId(), current.jobId(), Wire.of(current.state()));
            return;
        }
        if (current.nextAttemptAt() != null && current.nextAttemptAt().isAfter(clock.now())) {
            log.debug("Task {} of job {} waits for its retry at {}, the early command is ignored", current.taskId(), current.jobId(), current.nextAttemptAt());
            return;
        }
        save(current, run(job.get(), current));
    }

    private static String mismatch(TaskCommand command, Job job, JobTask task) {
        if (!task.jobId().equals(command.jobId())) {
            return "the task belongs to job " + task.jobId();
        }
        if (!task.originId().equals(command.originId())) {
            return "the command references origin id '%s', the task '%s'".formatted(command.originId(), task.originId());
        }
        if (!Objects.equals(task.referenceVersion(), command.referenceVersion())) {
            return "the command references version '%s', the task '%s'".formatted(command.referenceVersion(), task.referenceVersion());
        }
        if (command.type() == JobType.UPSERT && (!Objects.equals(job.request().messageName(), command.messageName())
                                                 || !Objects.equals(job.request().topicName(), command.topicName()))) {
            return "the command references message '%s' on topic '%s', the job '%s' on '%s'".formatted(command.messageName(),
                    command.topicName(), job.request().messageName(), job.request().topicName());
        }
        return null;
    }

    private TaskResult run(Job job, JobTask task) {
        Timer.Sample sample = Timer.start(meterRegistry);
        TaskResult result;
        try {
            result = switch (job.type()) {
                case UPSERT -> upsertExecutor.execute(job, task);
                case DELETE -> throw new IllegalStateException("Delete jobs are not executed by this version");
            };
        } catch (JobStorageUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // A defect must not keep the job open forever
            log.error("Unexpected error while running task {} of job {}", task.taskId(), job.jobId(), e);
            result = TaskResult.failed(TaskErrorCode.UNEXPECTED_ERROR, e.toString(), null);
        }
        if (result instanceof Retry retry && task.attempts() + 1 >= properties.getTaskMaxAttempts()) {
            int attempts = task.attempts() + 1;
            TaskError error = retry.error();
            log.warn("Task {} of job {} failed after {} attempts: {}", task.taskId(), task.jobId(), attempts, error.message());
            TaskError finalError = new TaskError(error.code(), "Gave up after %d attempts: %s".formatted(attempts, error.message()),
                    error.targetIndex(), error.traceId());
            result = new Completed(TaskState.FAILED, null, null, finalError);
        }
        sample.stop(Timer.builder(TASK_METRICS_NAME)
                .tag("job_type", Wire.of(job.type()))
                .tag("outcome", outcome(result))
                .register(meterRegistry));
        return result;
    }

    private void save(JobTask task, TaskResult result) {
        Instant now = clock.now();
        boolean saved = switch (result) {
            case Completed completed -> taskRepository.complete(task, completed.state(), completed.actualIndexMajorVersion(),
                    completed.actualIndexMinorVersion(), completed.error(), now);
            case Retry retry -> saveRetry(task, retry.error(), now);
        };
        if (!saved) {
            Optional<JobTask> current = taskRepository.find(task.taskId());
            if (current.isEmpty()) {
                log.info("Task {} of job {} was deleted concurrently, the result of this run is discarded", task.taskId(), task.jobId());
            } else if (current.get().state().isFinal() || current.get().nextAttemptAt() != null) {
                log.info("Task {} of job {} already has a durable outcome or scheduled retry, the result of this run is discarded",
                        task.taskId(), task.jobId());
            } else {
                throw new JobStorageUnavailableException(
                        "Task %s of job %s changed concurrently but has no durable outcome or scheduled retry; the command must be redelivered"
                                .formatted(task.taskId(), task.jobId()), null);
            }
        }
    }

    private boolean saveRetry(JobTask task, TaskError error, Instant now) {
        int attempts = task.attempts() + 1;
        Instant nextAttemptAt = now.plus(backoff(attempts, properties.getTaskRetryInitialBackoff(), properties.getTaskRetryMaxBackoff()));
        log.info("Task {} of job {} failed transiently (attempt {}), retrying at {}: {}", task.taskId(), task.jobId(), attempts,
                nextAttemptAt, error.message());
        return taskRepository.scheduleRetry(task, attempts, nextAttemptAt, error);
    }

    // attempts is the number of failed attempts so far: the first retry waits initial, every further one twice as long
    static Duration backoff(int attempts, Duration initial, Duration max) {
        Duration delay = initial;
        for (int i = 1; i < attempts && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private static String outcome(TaskResult result) {
        return switch (result) {
            case Completed completed -> Wire.of(completed.state());
            case Retry _ -> "retry";
        };
    }
}
