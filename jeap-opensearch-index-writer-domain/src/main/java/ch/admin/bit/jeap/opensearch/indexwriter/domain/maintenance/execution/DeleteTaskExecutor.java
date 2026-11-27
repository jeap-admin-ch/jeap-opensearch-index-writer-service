package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.DeletionOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTarget;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.TargetOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Deletes the id from every target stored with the job; never calls the provider and has no indexing flag. A target
// that already has a final outcome from an earlier attempt is not processed again, so recorded outcomes are kept.
@Slf4j
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
public class DeleteTaskExecutor {

    private final MaintenanceTargetWriter targetWriter;

    public TaskResult execute(Job job, JobTask task) {
        Map<Integer, TargetOutcome> recorded = task.targetOutcomes().stream()
                .collect(Collectors.toMap(TargetOutcome::indexMajorVersion, Function.identity(), (first, second) -> second));
        List<TargetOutcome> outcomes = new ArrayList<>(job.targets().size());
        for (JobTarget target : job.targets()) {
            TargetOutcome previous = recorded.get(target.indexMajorVersion());
            outcomes.add(previous != null && previous.isFinal() ? previous : delete(target, task.originId()));
        }
        return TaskResult.deleted(outcomes);
    }

    private TargetOutcome delete(JobTarget target, String originId) {
        try {
            DeletionOutcome outcome = targetWriter.delete(target.physicalIndex(), originId);
            return new TargetOutcome(target.indexMajorVersion(), target.physicalIndex(), outcome, null);
        } catch (TargetWriteException e) {
            var error = TaskResult.error(e.getCode(), e.getMessage(), target.physicalIndex());
            DeletionOutcome outcome = e.isRetryable() ? null : DeletionOutcome.FAILED;
            return new TargetOutcome(target.indexMajorVersion(), target.physicalIndex(), outcome, error);
        } catch (RuntimeException e) {
            // A defect on one target must not lose the outcomes of the others, which are saved with the same task update
            log.error("Unexpected error while deleting '{}' from {}", originId, target.physicalIndex(), e);
            var error = TaskResult.error(TaskErrorCode.UNEXPECTED_ERROR, e.toString(), target.physicalIndex());
            return new TargetOutcome(target.indexMajorVersion(), target.physicalIndex(), DeletionOutcome.FAILED, error);
        }
    }
}
