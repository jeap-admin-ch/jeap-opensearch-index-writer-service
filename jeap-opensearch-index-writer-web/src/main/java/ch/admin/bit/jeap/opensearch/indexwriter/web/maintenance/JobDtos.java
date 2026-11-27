package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public final class JobDtos {

    private JobDtos() {
    }

    public record Message(String messageName, String topicName) {
    }

    public record UpsertOperation(String indexType, String indexOperation,
                                  @Schema(description = "Schema major of the job, the highest configured major if omitted") Integer indexMajorVersion) {
    }

    public record UpsertReference(String id,
                                  @Schema(description = "Business reference version. Stored and reported only, it never selects a schema or influences a provider request")
                                  String version) {
    }

    public record UpsertJobRequest(Message message, UpsertOperation operation, List<UpsertReference> originReferences) {
    }

    public record DeleteReference(String id) {
    }

    public record DeleteJobRequest(String indexType,
                                   @Schema(description = "Schema major of the job, the highest configured major if omitted") Integer indexMajorVersion,
                                   List<DeleteReference> originReferences) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportOperation(String indexType, String indexOperation, Integer indexMajorVersion) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"total-references", "materialized-tasks", "open", "succeeded", "failed", "indexing-disabled"})
    public record ReportSummary(@JsonProperty("total-references") int totalReferences,
                                @JsonProperty("materialized-tasks") int materializedTasks,
                                int open, int succeeded, int failed,
                                @JsonProperty("indexing-disabled")
                                @Schema(description = "Tasks not run because the indexing flag of the job's operation was off; they count as not succeeded")
                                int indexingDisabled) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportError(String code, String message,
                              @JsonProperty("target-index") String targetIndex,
                              @JsonProperty("trace-id") String traceId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"task-id", "id", "state", "version", "actual-index-major-version", "actual-index-minor-version",
            "deletion-outcome", "attempts", "error"})
    public record ReportReference(@JsonProperty("task-id") String taskId, String id,
                                  @Schema(description = "open, succeeded, failed or indexing-disabled")
                                  String state, String version,
                                  @JsonProperty("actual-index-major-version")
                                  @Schema(description = "Schema major reported by the provider, only if known; distinct from the submitted version")
                                  Integer actualIndexMajorVersion,
                                  @JsonProperty("actual-index-minor-version") Integer actualIndexMinorVersion,
                                  @JsonProperty("deletion-outcome") String deletionOutcome,
                                  @Schema(description = "Failed attempts of an open task that is retried after a transient error; error is then its last error")
                                  Integer attempts,
                                  ReportError error) {
    }

    public record ReportPage(int limit, int returned,
                             @JsonProperty("next-cursor") @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportTarget(String selection, @JsonProperty("index-major-version") int indexMajorVersion,
                               @JsonProperty("physical-index") String physicalIndex, String rollover) {
    }

    // Only reported once the unfinished tasks of a stalled job were sent again
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReportDispatch(
            @Schema(description = "Rounds of sending the unfinished tasks again because the job made no progress")
            int redispatches,
            @JsonProperty("stuck-since")
            @Schema(description = "Set while an open job stalled again after the last round; nothing more is sent, its open tasks still finish if their commands arrive")
            String stuckSince) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"message", "operation", "indexType", "indexMajorVersion", "job-id", "job-type", "job-state",
            "started", "report-created", "started-by-name", "started-by-ext-id", "summary", "originReferences", "page",
            "target", "dispatch", "job-result", "completed"})
    public record JobReport(Message message, ReportOperation operation, String indexType, Integer indexMajorVersion,
                            @JsonProperty("job-id") String jobId,
                            @JsonProperty("job-type") String jobType,
                            @JsonProperty("job-state") String jobState,
                            String started,
                            @JsonProperty("report-created") String reportCreated,
                            @JsonProperty("started-by-name") String startedByName,
                            @JsonProperty("started-by-ext-id") String startedByExtId,
                            ReportSummary summary,
                            List<ReportReference> originReferences,
                            ReportPage page,
                            ReportTarget target,
                            ReportDispatch dispatch,
                            @JsonProperty("job-result") String jobResult,
                            String completed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message) {
    }
}
