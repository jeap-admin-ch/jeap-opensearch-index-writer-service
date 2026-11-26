package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskError;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import org.slf4j.MDC;

public sealed interface TaskResult {

    record Completed(TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                     TaskError error) implements TaskResult {
    }

    // A transient error: the task stays open and is retried until task-max-attempts is reached
    record Retry(TaskError error) implements TaskResult {
    }

    static Completed succeeded(int actualIndexMajorVersion, int actualIndexMinorVersion) {
        return new Completed(TaskState.SUCCEEDED, actualIndexMajorVersion, actualIndexMinorVersion, null);
    }

    static Completed indexingDisabled() {
        return new Completed(TaskState.INDEXING_DISABLED, null, null, null);
    }

    static Completed failed(TaskErrorCode code, String message, String targetIndex) {
        return failed(code, message, targetIndex, null, null);
    }

    static Completed failed(TaskErrorCode code, String message, String targetIndex,
                            Integer actualIndexMajorVersion, Integer actualIndexMinorVersion) {
        return new Completed(TaskState.FAILED, actualIndexMajorVersion, actualIndexMinorVersion, error(code, message, targetIndex));
    }

    static Retry retry(TaskErrorCode code, String message, String targetIndex) {
        return new Retry(error(code, message, targetIndex));
    }

    private static TaskError error(TaskErrorCode code, String message, String targetIndex) {
        return new TaskError(code.name(), message, targetIndex, MDC.get("traceId"));
    }
}
