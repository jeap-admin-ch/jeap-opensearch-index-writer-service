package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTasksNotDeletedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

public class InMemoryJobStorage implements JobRepository, TaskRepository {

    public static final MaterializationExecutor RUN_DIRECTLY = task -> {
        task.run();
        return true;
    };

    private static final Comparator<Job> ORDER = Comparator.comparing(Job::startedAt).thenComparing(j -> j.jobId().toString());

    public final Map<UUID, Job> jobs = new HashMap<>();
    public final Map<UUID, JobTask> tasks = new HashMap<>();
    final Set<UUID> failingTaskDeletes = new HashSet<>();
    int fullReads;
    boolean rejectRefresh;
    // Stored on create, as if a concurrent submission had created this job first
    Job concurrentlyCreated;
    final Set<UUID> rejectedTaskCreations = new HashSet<>();

    public static Job job(UUID jobId, JobRequest request, JobState state, MaterializationState materialization,
                   Instant startedAt, Instant completedAt) {
        JobTarget target = new JobTarget(MajorSelection.EXPLICIT, 1, "index_v1-000001", "uuid", JobTarget.ROLLOVER_NOT_DETECTED);
        return new Job(jobId, JobSubmissionService.requestHash(request), state, null, request.references().size(), materialization,
                startedAt, completedAt, new SubmittedBy("tester", null), request, List.of(target), Dispatch.NONE, 0, 1);
    }

    public static Job withTargets(Job job, List<JobTarget> targets) {
        return new Job(job.jobId(), job.requestHash(), job.state(), job.result(), job.expectedTaskCount(), job.materializationState(),
                job.startedAt(), job.completedAt(), job.submittedBy(), job.request(), targets, job.dispatch(), job.seqNo(), job.primaryTerm());
    }

    public static JobRequest upsert(String... ids) {
        return new JobRequest(JobType.UPSERT, "Msg", "topic", "DecreeDocument", 1,
                Arrays.stream(ids).map(id -> new JobReference(id, "v-" + id)).toList());
    }

    public static JobRequest delete(String... ids) {
        return new JobRequest(JobType.DELETE, null, null, "DecreeDocument", 1,
                Arrays.stream(ids).map(id -> new JobReference(id, null)).toList());
    }

    public void store(Job job) {
        jobs.put(job.jobId(), job);
    }

    public void storeTask(Job job, int position, TaskState state, Instant completedAt) {
        JobReference reference = job.request().references().get(position);
        UUID taskId = JobModel.taskId(job.jobId(), reference.id());
        tasks.put(taskId, new JobTask(job.jobId(), taskId, position, reference.id(), reference.version(), state,
                null, null, null, null, job.startedAt(), completedAt, 0, null, 0, 1));
    }

    public void storeTask(JobTask task) {
        tasks.put(task.taskId(), task);
    }

    public JobTask task(Job job, int position) {
        return tasks.get(JobModel.taskId(job.jobId(), job.request().references().get(position).id()));
    }

    public List<JobTask> tasksOf(UUID jobId) {
        return tasks.values().stream().filter(t -> t.jobId().equals(jobId)).sorted(Comparator.comparingInt(JobTask::position)).toList();
    }

    @Override
    public boolean create(Job job) {
        if (concurrentlyCreated != null && concurrentlyCreated.jobId().equals(job.jobId())) {
            jobs.put(job.jobId(), concurrentlyCreated);
            return false;
        }
        return jobs.putIfAbsent(job.jobId(), job) == null;
    }

    @Override
    public Optional<Job> find(UUID jobId, boolean withReferences) {
        if (withReferences) {
            fullReads++;
        }
        return Optional.ofNullable(jobs.get(jobId))
                .map(j -> withReferences ? j : copy(j, j.state(), j.result(), j.materializationState(), j.completedAt(), j.request().withoutReferences()));
    }

    @Override
    public boolean markMaterialized(Job job) {
        return update(job, copy(job, job.state(), job.result(), MaterializationState.MATERIALIZED, job.completedAt(), null));
    }

    @Override
    public List<Job> findMaterializingStartedBefore(Instant startedBefore, Job after, int limit) {
        return page(j -> j.materializationState() == MaterializationState.MATERIALIZING && j.startedAt().isBefore(startedBefore), after, limit);
    }

    @Override
    public List<Job> findOpenMaterialized(Job after, int limit) {
        return page(j -> j.state() == JobState.OPEN && j.materializationState() == MaterializationState.MATERIALIZED, after, limit);
    }

    @Override
    public boolean failMaterialization(Job job, Instant completedAt) {
        return update(job, copy(job, JobState.COMPLETED, JobResult.FAILED, MaterializationState.FAILED, completedAt, null));
    }

    @Override
    public boolean complete(Job job, JobResult result, Instant completedAt) {
        return update(job, copy(job, JobState.COMPLETED, result, job.materializationState(), completedAt, null));
    }

    @Override
    public List<Job> findCompletedBefore(Instant completedBefore, Job after, int limit) {
        return page(j -> j.state() == JobState.COMPLETED && j.completedAt().isBefore(completedBefore), after, limit);
    }

    @Override
    public void delete(UUID jobId) {
        jobs.remove(jobId);
    }

    @Override
    public void createMissing(List<JobTask> batch) {
        if (!batch.isEmpty() && rejectedTaskCreations.contains(batch.getFirst().jobId())) {
            throw new JobExceptions.JobStorageRejectedException("simulated rejection");
        }
        batch.forEach(t -> tasks.putIfAbsent(t.taskId(), t));
    }

    @Override
    public void refresh() {
        if (rejectRefresh) {
            throw new JobExceptions.JobStorageRejectedException("simulated refresh rejection");
        }
        // visible immediately
    }

    @Override
    public List<JobTask> findByPositionRange(UUID jobId, int fromInclusive, int toExclusive) {
        return tasksOf(jobId).stream().filter(t -> t.position() >= fromInclusive && t.position() < toExclusive).toList();
    }

    @Override
    public TaskCounts counts(UUID jobId) {
        List<JobTask> ofJob = tasksOf(jobId);
        Instant last = ofJob.stream().map(JobTask::completedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new TaskCounts(ofJob.size(), (int) ofJob.stream().filter(t -> t.state() == TaskState.SUCCEEDED).count(),
                (int) ofJob.stream().filter(t -> t.state() == TaskState.FAILED).count(),
                (int) ofJob.stream().filter(t -> t.state() == TaskState.INDEXING_DISABLED).count(), last);
    }

    @Override
    public void deleteByJobId(UUID jobId) {
        if (failingTaskDeletes.contains(jobId)) {
            throw new JobTasksNotDeletedException("simulated version conflict");
        }
        tasks.values().removeIf(t -> t.jobId().equals(jobId));
    }

    @Override
    public Optional<Job> updateDispatch(Job job, Dispatch dispatch) {
        Job current = jobs.get(job.jobId());
        if (current == null || current.seqNo() != job.seqNo()) {
            return Optional.empty();
        }
        Job updated = current.withDispatch(dispatch, current.seqNo() + 1, current.primaryTerm());
        jobs.put(updated.jobId(), updated);
        return Optional.of(job.withDispatch(dispatch, updated.seqNo(), updated.primaryTerm()));
    }

    @Override
    public Optional<JobTask> find(UUID taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public boolean complete(JobTask task, TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                            List<TargetOutcome> targetOutcomes, TaskError error, Instant completedAt) {
        return updateTask(task, t -> new JobTask(t.jobId(), t.taskId(), t.position(), t.originId(), t.referenceVersion(), state,
                actualIndexMajorVersion, actualIndexMinorVersion, outcomes(t, targetOutcomes), error, t.createdAt(), completedAt,
                t.attempts(), null, t.seqNo() + 1, t.primaryTerm()));
    }

    @Override
    public boolean scheduleRetry(JobTask task, int attempts, Instant nextAttemptAt, List<TargetOutcome> targetOutcomes, TaskError lastError) {
        return updateTask(task, t -> new JobTask(t.jobId(), t.taskId(), t.position(), t.originId(), t.referenceVersion(), t.state(),
                t.actualIndexMajorVersion(), t.actualIndexMinorVersion(), outcomes(t, targetOutcomes), lastError, t.createdAt(),
                t.completedAt(), attempts, nextAttemptAt, t.seqNo() + 1, t.primaryTerm()));
    }

    // Like the OpenSearch repository: an empty list keeps the stored outcomes
    private static List<TargetOutcome> outcomes(JobTask task, List<TargetOutcome> targetOutcomes) {
        return targetOutcomes.isEmpty() ? task.targetOutcomes() : targetOutcomes;
    }

    @Override
    public void clearScheduledRetries(List<JobTask> cleared) {
        cleared.forEach(task -> updateTask(task, t -> new JobTask(t.jobId(), t.taskId(), t.position(), t.originId(),
                t.referenceVersion(), t.state(), t.actualIndexMajorVersion(), t.actualIndexMinorVersion(), t.targetOutcomes(),
                t.error(), t.createdAt(), t.completedAt(), t.attempts(), null, t.seqNo() + 1, t.primaryTerm())));
    }

    @Override
    public List<JobTask> findDueRetries(UUID jobId, Instant dueAt, int afterPosition, int limit) {
        return tasksOf(jobId).stream()
                .filter(t -> t.state() == TaskState.OPEN && t.nextAttemptAt() != null && !t.nextAttemptAt().isAfter(dueAt))
                .filter(t -> t.position() > afterPosition).limit(limit).toList();
    }

    @Override
    public List<JobTask> findOpenUnscheduled(UUID jobId, int afterPosition, int limit) {
        return tasksOf(jobId).stream()
                .filter(t -> t.state() == TaskState.OPEN && t.nextAttemptAt() == null)
                .filter(t -> t.position() > afterPosition).limit(limit).toList();
    }

    private boolean updateTask(JobTask expected, java.util.function.UnaryOperator<JobTask> change) {
        JobTask current = tasks.get(expected.taskId());
        if (current == null || current.seqNo() != expected.seqNo()) {
            return false;
        }
        tasks.put(current.taskId(), change.apply(current));
        return true;
    }

    private List<Job> page(Predicate<Job> filter, Job after, int limit) {
        return jobs.values().stream().filter(filter)
                .filter(j -> after == null || ORDER.compare(j, after) > 0)
                .sorted(ORDER).limit(limit).toList();
    }

    private boolean update(Job expected, Job updated) {
        Job current = jobs.get(expected.jobId());
        if (current == null || current.seqNo() != expected.seqNo()) {
            return false;
        }
        jobs.put(updated.jobId(), updated);
        return true;
    }

    // Keeps the stored references, the job passed to an update may have been read without them
    private Job copy(Job job, JobState state, JobResult result, MaterializationState materialization, Instant completedAt,
                     JobRequest request) {
        JobRequest stored = request != null ? request : jobs.getOrDefault(job.jobId(), job).request();
        Dispatch dispatch = jobs.getOrDefault(job.jobId(), job).dispatch();
        return new Job(job.jobId(), job.requestHash(), state, result, job.expectedTaskCount(), materialization, job.startedAt(),
                completedAt, job.submittedBy(), stored, job.targets(), dispatch, job.seqNo() + (request == null ? 1 : 0), job.primaryTerm());
    }
}
