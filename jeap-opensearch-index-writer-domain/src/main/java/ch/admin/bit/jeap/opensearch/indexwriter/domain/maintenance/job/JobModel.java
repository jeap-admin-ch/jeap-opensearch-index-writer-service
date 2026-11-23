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

    // seqNo and primaryTerm are the storage versioning used for conditional updates
    public record Job(UUID jobId, String requestHash, JobState state, JobResult result, int expectedTaskCount,
                      MaterializationState materializationState, Instant startedAt, Instant completedAt,
                      SubmittedBy submittedBy, JobRequest request, JobTarget target, long seqNo, long primaryTerm) {

        public JobType type() {
            return request.type();
        }
    }

    public record TaskError(String code, String message, String targetIndex, String traceId) {
    }

    public record JobTask(UUID jobId, UUID taskId, int position, String originId, String referenceVersion,
                          TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                          String deletionOutcome, TaskError error, Instant createdAt, Instant completedAt) {
    }

    public record TaskCounts(int total, int succeeded, int failed, Instant lastCompletedAt) {
    }

    public record ResolvedTarget(String physicalIndex, String physicalIndexUuid) {
    }

    public record JobSubmission(UUID jobId, JobRequest request, SubmittedBy submittedBy) {
    }

    public record ReportSummary(int totalReferences, int materializedTasks, int open, int succeeded, int failed) {
    }

    public record ReportEntry(UUID taskId, String originId, String version, TaskState state,
                              Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                              String deletionOutcome, TaskError error) {
    }

    public record JobReport(Job job, Instant reportCreated, ReportSummary summary, List<ReportEntry> entries,
                            int limit, String nextCursor) {
    }

    public static UUID taskId(UUID jobId, String originId) {
        return UUID.nameUUIDFromBytes((jobId + "/" + originId).getBytes(StandardCharsets.UTF_8));
    }
}
