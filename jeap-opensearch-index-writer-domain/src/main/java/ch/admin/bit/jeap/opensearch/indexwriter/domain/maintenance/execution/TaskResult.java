package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.DeletionOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TargetOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TaskError;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import org.slf4j.MDC;

import java.util.List;

// targetOutcomes is empty for upsert tasks and holds one entry per job target for delete tasks
public sealed interface TaskResult {

    record Completed(TaskState state, Integer actualIndexMajorVersion, Integer actualIndexMinorVersion,
                     List<TargetOutcome> targetOutcomes, TaskError error) implements TaskResult {

        public Completed {
            targetOutcomes = List.copyOf(targetOutcomes);
        }
    }

    // A transient error: the task stays open and is retried until task-max-attempts is reached. Targets of a delete
    // task already processed keep their outcome, the others have none yet.
    record Retry(TaskError error, List<TargetOutcome> targetOutcomes) implements TaskResult {

        public Retry {
            targetOutcomes = List.copyOf(targetOutcomes);
        }

        // After the last attempt every target still without outcome fails with the final error
        public Completed exhausted(int attempts) {
            TaskError finalError = gaveUp(error, attempts);
            List<TargetOutcome> outcomes = targetOutcomes.stream()
                    .map(o -> o.isFinal() ? o : new TargetOutcome(o.indexMajorVersion(), o.targetIndex(), DeletionOutcome.FAILED,
                            gaveUp(o.error() == null ? error : o.error(), attempts)))
                    .toList();
            return new Completed(TaskState.FAILED, null, null, outcomes, finalError);
        }

        private static TaskError gaveUp(TaskError error, int attempts) {
            return new TaskError(error.code(), "Gave up after %d attempts: %s".formatted(attempts, error.message()),
                    error.targetIndex(), error.traceId());
        }
    }

    static Completed succeeded(int actualIndexMajorVersion, int actualIndexMinorVersion) {
        return new Completed(TaskState.SUCCEEDED, actualIndexMajorVersion, actualIndexMinorVersion, List.of(), null);
    }

    static Completed indexingDisabled() {
        return new Completed(TaskState.INDEXING_DISABLED, null, null, List.of(), null);
    }

    static Completed failed(TaskErrorCode code, String message, String targetIndex) {
        return failed(code, message, targetIndex, null, null);
    }

    static Completed failed(TaskErrorCode code, String message, String targetIndex,
                            Integer actualIndexMajorVersion, Integer actualIndexMinorVersion) {
        return new Completed(TaskState.FAILED, actualIndexMajorVersion, actualIndexMinorVersion, List.of(),
                error(code, message, targetIndex));
    }

    static Retry retry(TaskErrorCode code, String message, String targetIndex) {
        return new Retry(error(code, message, targetIndex), List.of());
    }

    // A delete task succeeds only if every target ended as deleted or already absent; a failed target fails the task,
    // the error of each target is reported with it
    static TaskResult deleted(List<TargetOutcome> outcomes) {
        TargetOutcome pending = outcomes.stream().filter(o -> !o.isFinal()).findFirst().orElse(null);
        if (pending != null) {
            return new Retry(pending.error(), outcomes);
        }
        boolean succeeded = outcomes.stream().allMatch(o -> o.outcome().isSuccess());
        return new Completed(succeeded ? TaskState.SUCCEEDED : TaskState.FAILED, null, null, outcomes, null);
    }

    static TaskError error(TaskErrorCode code, String message, String targetIndex) {
        return new TaskError(code.name(), message, targetIndex, MDC.get("traceId"));
    }
}
