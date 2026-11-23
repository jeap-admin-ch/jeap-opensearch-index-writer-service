package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobTasksNotDeletedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

class InMemoryJobStorage implements JobRepository, TaskRepository {

    static final MaterializationExecutor RUN_DIRECTLY = task -> {
        task.run();
        return true;
    };

    private static final Comparator<Job> ORDER = Comparator.comparing(Job::startedAt).thenComparing(j -> j.jobId().toString());

    final Map<UUID, Job> jobs = new HashMap<>();
    final Map<UUID, JobTask> tasks = new HashMap<>();
    final Set<UUID> failingTaskDeletes = new HashSet<>();
    int fullReads;
    boolean rejectRefresh;
    // Stored on create, as if a concurrent submission had created this job first
    Job concurrentlyCreated;
    final Set<UUID> rejectedTaskCreations = new HashSet<>();

    static Job job(UUID jobId, JobRequest request, JobState state, MaterializationState materialization,
                   Instant startedAt, Instant completedAt) {
        JobTarget target = new JobTarget(MajorSelection.EXPLICIT, 1, "index_v1-000001", "uuid", JobTarget.ROLLOVER_NOT_DETECTED);
        return new Job(jobId, JobSubmissionService.requestHash(request), state, null, request.references().size(), materialization,
                startedAt, completedAt, new SubmittedBy("tester", null), request, target, 0, 1);
    }

    static JobRequest upsert(String... ids) {
        return new JobRequest(JobType.UPSERT, "Msg", "topic", "DecreeDocument", 1,
                Arrays.stream(ids).map(id -> new JobReference(id, "v-" + id)).toList());
    }

    static JobRequest delete(String... ids) {
        return new JobRequest(JobType.DELETE, null, null, "DecreeDocument", 1,
                Arrays.stream(ids).map(id -> new JobReference(id, null)).toList());
    }

    void store(Job job) {
        jobs.put(job.jobId(), job);
    }

    void storeTask(Job job, int position, TaskState state, Instant completedAt) {
        JobReference reference = job.request().references().get(position);
        UUID taskId = JobModel.taskId(job.jobId(), reference.id());
        tasks.put(taskId, new JobTask(job.jobId(), taskId, position, reference.id(), reference.version(), state,
                null, null, null, null, job.startedAt(), completedAt));
    }

    List<JobTask> tasksOf(UUID jobId) {
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
                (int) ofJob.stream().filter(t -> t.state() == TaskState.FAILED).count(), last);
    }

    @Override
    public void deleteByJobId(UUID jobId) {
        if (failingTaskDeletes.contains(jobId)) {
            throw new JobTasksNotDeletedException("simulated version conflict");
        }
        tasks.values().removeIf(t -> t.jobId().equals(jobId));
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
        return new Job(job.jobId(), job.requestHash(), state, result, job.expectedTaskCount(), materialization, job.startedAt(),
                completedAt, job.submittedBy(), stored, job.target(), job.seqNo() + (request == null ? 1 : 0), job.primaryTerm());
    }
}
