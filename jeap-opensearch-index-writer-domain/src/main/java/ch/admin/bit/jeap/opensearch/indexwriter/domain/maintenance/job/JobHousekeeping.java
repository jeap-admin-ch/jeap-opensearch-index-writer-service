package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.JobDispatcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskDispatchException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskCounts;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

// Every step is idempotent and safe to run on several instances at once
@Service
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
public class JobHousekeeping {

    private static final int BATCH = 100;

    private final MaintenanceProperties properties;
    private final JobSubmissionService submissionService;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final JobDispatcher dispatcher;
    private final JobClock clock;

    public void run() {
        step("resume task creation", this::resumeMaterialization);
        step("send task commands", this::dispatchCommands);
        step("complete jobs", this::completeFinishedJobs);
        step("delete expired jobs", this::deleteExpiredJobs);
    }

    private void step(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Job housekeeping step '{}' failed, it is retried with the next run", name, e);
        }
    }

    public void resumeMaterialization() {
        Instant startedBefore = clock.now().minus(properties.getMaterializationResumeAfter());
        forEachCandidate(after -> jobRepository.findMaterializingStartedBefore(startedBefore, after, BATCH), job ->
                jobRepository.find(job.jobId(), true).ifPresent(full -> {
                    log.info("Resuming task creation of job {}", full.jobId());
                    submissionService.materialize(full);
                }));
    }

    // Resumes interrupted dispatches, sends due retries and the commands of stalled jobs again
    public void dispatchCommands() {
        forEachCandidate(after -> jobRepository.findOpenMaterialized(after, BATCH), dispatcher::dispatch);
    }

    public void completeFinishedJobs() {
        forEachCandidate(after -> jobRepository.findOpenMaterialized(after, BATCH), job -> {
            TaskCounts counts = taskRepository.counts(job.jobId());
            if (counts.total() != job.expectedTaskCount() || counts.finished() != job.expectedTaskCount()) {
                return;
            }
            JobResult result = resultOf(counts);
            Instant completedAt = counts.lastCompletedAt() != null ? counts.lastCompletedAt() : clock.now();
            if (jobRepository.complete(job, result, completedAt)) {
                log.info("Job {} completed: {}", job.jobId(), Wire.of(result));
            }
        });
    }

    public void deleteExpiredJobs() {
        Instant completedBefore = clock.now().minus(properties.getRetention());
        forEachCandidate(after -> jobRepository.findCompletedBefore(completedBefore, after, BATCH), job -> {
            taskRepository.deleteByJobId(job.jobId());
            jobRepository.delete(job.jobId());
            log.info("Deleted job {} and its tasks after the retention of {}", job.jobId(), properties.getRetention());
        });
    }

    // Visits every candidate, not only the first page, and isolates failures so one broken job cannot block the others
    private void forEachCandidate(Function<Job, List<Job>> page, Consumer<Job> action) {
        Job after = null;
        List<Job> jobs;
        do {
            jobs = page.apply(after);
            for (Job job : jobs) {
                try {
                    action.accept(job);
                } catch (JobStorageUnavailableException | TaskDispatchException e) {
                    // The next jobs would fail the same way, waiting for each would delay the remaining steps
                    throw e;
                } catch (RuntimeException e) {
                    log.warn("Job housekeeping failed for job {}, it is retried with the next run", job.jobId(), e);
                }
            }
            after = jobs.isEmpty() ? null : jobs.getLast();
        } while (jobs.size() == BATCH);
    }

    // Tasks ended as indexing-disabled count as not succeeded
    private static JobResult resultOf(TaskCounts counts) {
        if (counts.succeeded() == counts.total()) {
            return JobResult.SUCCEEDED;
        }
        return counts.succeeded() == 0 ? JobResult.FAILED : JobResult.PARTIALLY_SUCCEEDED;
    }
}
