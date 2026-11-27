package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JobModel {

    private JobModel() {
    }

    // The version is stored and reported only, it never selects the schema major or a provider request
    public record JobReference(String id, String version) {
    }

    // indexMajorVersion is the submitted selector (null if omitted); messageName and topicName are set for upsert jobs only
    public record JobRequest(JobType type, String messageName, String topicName, String indexType,
                             Integer indexMajorVersion, List<JobReference> references) {
        public JobRequest withoutReferences() {
            return new JobRequest(type, messageName, topicName, indexType, indexMajorVersion, List.of());
        }
    }

    public record SubmittedBy(String name, String extId) {
    }

    public record JobTarget(MajorSelection selection, int indexMajorVersion, String physicalIndex,
                            String physicalIndexUuid, String rolloverDetection) {
        public static final String ROLLOVER_NOT_DETECTED = "not-detected";
        public static final String ROLLOVER_UNDETERMINED = "undetermined";
    }

    // cursor: tasks below this position have been dispatched. owner and leaseUntil: the instance currently dispatching.
    // dispatchedAt: the last time commands were sent for the job. redispatches: rounds of sending the unfinished tasks
    // of a stalled job again. stuckAt: set once the job stalled again after the last allowed round, nothing is resent then
    public record Dispatch(int cursor, String owner, Instant leaseUntil, Instant dispatchedAt, int redispatches, Instant stuckAt) {
        public static final Dispatch NONE = new Dispatch(0, null, null, null, 0, null);

        public boolean isLeasedByOtherAt(String instance, Instant now) {
            return owner != null && !owner.equals(instance) && leaseUntil != null && leaseUntil.isAfter(now);
        }

        public Dispatch leased(String instance, Instant until) {
            return new Dispatch(cursor, instance, until, dispatchedAt, redispatches, stuckAt);
        }

        public Dispatch released() {
            return new Dispatch(cursor, null, null, dispatchedAt, redispatches, stuckAt);
        }

        public Dispatch sent(int newCursor, Instant until, Instant at) {
            return new Dispatch(newCursor, owner, until, at, redispatches, stuckAt);
        }

        public Dispatch redispatched() {
            return new Dispatch(cursor, owner, leaseUntil, dispatchedAt, redispatches + 1, stuckAt);
        }

        public Dispatch stuck(Instant at) {
            return new Dispatch(cursor, owner, leaseUntil, dispatchedAt, redispatches, at);
        }
    }

    // seqNo and primaryTerm are the storage versioning used for conditional updates
    // targets holds one entry per selected major: exactly one for upsert jobs, one or more for delete jobs
    public record Job(UUID jobId, String requestHash, JobState state, JobResult result, int expectedTaskCount,
                      MaterializationState materializationState, Instant startedAt, Instant completedAt,
                      SubmittedBy submittedBy, JobRequest request, List<JobTarget> targets, Dispatch dispatch,
                      long seqNo, long primaryTerm) {

        public Job {
            targets = List.copyOf(targets);
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("A job needs at least one target");
            }
        }

        public JobType type() {
            return request.type();
        }

        public MajorSelection selection() {
            return targets.getFirst().selection();
        }

        // The single target of a job that has exactly one, e.g. every upsert job
        public JobTarget target() {
            if (targets.size() != 1) {
                throw new IllegalStateException("Job %s has %d targets".formatted(jobId, targets.size()));
            }
            return targets.getFirst();
        }

        public boolean isFullyDispatched() {
            return dispatch.cursor() >= expectedTaskCount;
        }

        public Job withDispatch(Dispatch newDispatch, long newSeqNo, long newPrimaryTerm) {
            return new Job(jobId, requestHash, state, result, expectedTaskCount, materializationState, startedAt, completedAt,
                    submittedBy, request, targets, newDispatch, newSeqNo, newPrimaryTerm);
        }
    }

    public record TaskError(String code, String message, String targetIndex, String traceId) {
    }

    // The result of a delete task for one target. outcome is null while the target is still to be processed again
    // after a transient error, error is then the last one.
    public record TargetOutcome(int indexMajorVersion, String targetIndex, DeletionOutcome outcome, TaskError error) {

        public boolean isFinal() {
            return outcome != null;
        }
    }

    // error is the final error of a failed task, or the last transient error of an open task waiting for a retry.
    // nextAttemptAt is set while a retry is scheduled. targetOutcomes is set for delete tasks once a target was processed
    public record JobTask(UUID jobId, UUID taskId, int position, String originId, String referenceVersion,
                          TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                          List<TargetOutcome> targetOutcomes, TaskError error, Instant createdAt, Instant completedAt,
                          int attempts, Instant nextAttemptAt, long seqNo, long primaryTerm) {

        public JobTask {
            targetOutcomes = targetOutcomes == null ? List.of() : List.copyOf(targetOutcomes);
        }

        public static JobTask open(UUID jobId, int position, String originId, String referenceVersion, Instant createdAt) {
            return new JobTask(jobId, JobModel.taskId(jobId, originId), position, originId, referenceVersion, TaskState.OPEN,
                    null, null, List.of(), null, createdAt, null, 0, null, 0, 0);
        }
    }

    public record TaskCounts(int total, int succeeded, int failed, int indexingDisabled, Instant lastCompletedAt) {

        public int finished() {
            return succeeded + failed + indexingDisabled;
        }
    }

    public record ResolvedTarget(String physicalIndex, String physicalIndexUuid) {
    }

    public record JobSubmission(UUID jobId, JobRequest request, SubmittedBy submittedBy) {
    }

    public record ReportSummary(int totalReferences, int materializedTasks, int open, int succeeded, int failed,
                                int indexingDisabled) {
    }

    // attempts is set for open tasks that already failed transiently, error is then their last error; targets is set
    // for delete tasks, one entry per job target, without outcome while the target is not processed yet
    public record ReportEntry(UUID taskId, String originId, String version, TaskState state,
                              Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                              List<TargetOutcome> targets, TaskError error, Integer attempts) {
    }

    public record JobReport(Job job, Instant reportCreated, ReportSummary summary, List<ReportEntry> entries,
                            int limit, String nextCursor) {
    }

    public static UUID taskId(UUID jobId, String originId) {
        return UUID.nameUUIDFromBytes((jobId + "/" + originId).getBytes(StandardCharsets.UTF_8));
    }
}
