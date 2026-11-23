package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
public class JobReportService {

    static final TaskError CREATION_REJECTED = new TaskError("TASK_CREATION_REJECTED",
            "The job storage rejected the creation of the job's tasks; the job was ended as failed", null, null);

    private final MaintenanceProperties properties;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final JobClock clock;
    private final JobReferenceCache referenceCache;


    // Positions not yet materialized as tasks are reported from the stored request as open
    public JobReport report(UUID jobId, JobType expectedType, String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? properties.getDefaultPageSize() : requestedLimit;
        if (limit < 1 || limit > properties.getMaxPageSize()) {
            throw new InvalidJobException("limit must be between 1 and %d".formatted(properties.getMaxPageSize()));
        }
        Job job = jobRepository.find(jobId, false)
                .filter(j -> j.type() == expectedType)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        int from = parseCursor(cursor, job.expectedTaskCount());
        int to = Math.min(from + limit, job.expectedTaskCount());
        Map<Integer, JobTask> tasksByPosition = new HashMap<>();
        taskRepository.findByPositionRange(jobId, from, to).forEach(t -> tasksByPosition.put(t.position(), t));

        List<JobReference> references = tasksByPosition.size() < to - from ? references(job) : List.of();
        List<ReportEntry> entries = new ArrayList<>(to - from);
        // A job whose task creation was rejected never runs its remaining references, so they are reported as failed
        boolean creationFailed = job.materializationState() == MaterializationState.FAILED;
        for (int position = from; position < to; position++) {
            JobTask task = tasksByPosition.get(position);
            ReportEntry entry = task != null ? entry(task, job.type()) : unmaterializedEntry(jobId, references.get(position), job.type());
            entries.add(creationFailed && entry.state() == TaskState.OPEN ? asCreationFailed(entry) : entry);
        }

        TaskCounts counts = taskRepository.counts(jobId);
        int failed = creationFailed ? job.expectedTaskCount() - counts.succeeded() : counts.failed();
        ReportSummary summary = new ReportSummary(job.expectedTaskCount(), counts.total(),
                job.expectedTaskCount() - counts.succeeded() - failed, counts.succeeded(), failed);
        return new JobReport(job, clock.now(), summary, entries, limit, to < job.expectedTaskCount() ? Integer.toString(to) : null);
    }

    private List<JobReference> references(Job job) {
        Optional<List<JobReference>> cached = referenceCache.get(job);
        if (cached.isPresent()) {
            return cached.get();
        }
        Job full = jobRepository.find(job.jobId(), true).orElseThrow(() -> new JobNotFoundException(job.jobId()));
        List<JobReference> references = full.request().references();
        if (!full.requestHash().equals(job.requestHash()) || references.size() != job.expectedTaskCount()) {
            throw new IllegalStateException("Job %s changed while its report was created".formatted(job.jobId()));
        }
        referenceCache.put(job, references);
        return references;
    }

    private static int parseCursor(String cursor, int total) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int position = Integer.parseInt(cursor);
            if (position >= 0 && position <= total) {
                return position;
            }
        } catch (NumberFormatException _) {
            // reported below
        }
        throw new InvalidJobException("Invalid cursor '%s'".formatted(cursor));
    }

    private static ReportEntry entry(JobTask task, JobType type) {
        boolean upsert = type == JobType.UPSERT;
        return new ReportEntry(task.taskId(), task.originId(), upsert ? task.referenceVersion() : null, task.state(),
                upsert ? task.actualIndexMajorVersion() : null, upsert ? task.actualIndexMinorVersion() : null,
                upsert ? null : task.deletionOutcome(), task.error());
    }

    private static ReportEntry asCreationFailed(ReportEntry entry) {
        return new ReportEntry(entry.taskId(), entry.originId(), entry.version(), TaskState.FAILED,
                entry.actualIndexMajorVersion(), entry.actualIndexMinorVersion(), entry.deletionOutcome(), CREATION_REJECTED);
    }

    private static ReportEntry unmaterializedEntry(UUID jobId, JobReference reference, JobType type) {
        return new ReportEntry(JobModel.taskId(jobId, reference.id()), reference.id(),
                type == JobType.UPSERT ? reference.version() : null, TaskState.OPEN, null, null, null, null);
    }
}
