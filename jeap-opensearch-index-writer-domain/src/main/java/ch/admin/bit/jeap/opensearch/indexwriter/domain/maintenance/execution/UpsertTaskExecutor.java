package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemFetcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobRequest;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTarget;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.util.Objects;
import java.util.Optional;

// Fetches the current data in the job's schema major (never a historical version) and replaces the document in the stored target
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
public class UpsertTaskExecutor {

    private final MessageConfigurationRepository messageConfigurationRepository;
    private final IndexTypeRepository indexTypeRepository;
    private final FeatureManager featureManager;
    private final CurrentSearchItemFetcher fetcher;
    private final TargetIndexGuard targetGuard;
    private final MaintenanceTargetWriter targetWriter;

    public TaskResult execute(Job job, JobTask task) {
        JobRequest request = job.request();
        JobTarget target = job.target();
        Optional<MessageOperationConfig> operation = upsertOperation(request);
        if (operation.isEmpty()) {
            return TaskResult.failed(TaskErrorCode.CONFIGURATION_MISSING,
                    "Message '%s' on topic '%s' has no UPSERT operation for index type '%s' anymore"
                            .formatted(request.messageName(), request.topicName(), request.indexType()), null);
        }
        String flag = operation.get().featureFlag();
        if (flag != null && !featureManager.isActive(new NamedFeature(flag))) {
            return TaskResult.indexingDisabled();
        }
        Optional<IndexType<?>> indexType = indexTypeRepository.findByOriginTypeAndMajorVersion(request.indexType(), target.indexMajorVersion());
        if (indexType.isEmpty()) {
            return TaskResult.failed(TaskErrorCode.INDEX_TYPE_NOT_CONFIGURED,
                    "Index type '%s' with schema major %d is no longer deployed".formatted(request.indexType(), target.indexMajorVersion()), null);
        }
        try {
            targetGuard.requireTarget(job);
            SearchItemIndexed<?> item = fetcher.fetch(operation.get().uri(), operation.get().oauthClientId(), indexType.get(), task.originId());
            targetWriter.upsert(target.physicalIndex(), task.originId(), item);
            return TaskResult.succeeded(item.searchItem().majorVersion(), item.searchItem().minorVersion());
        } catch (CurrentSearchItemException e) {
            return providerRejection(e, target);
        } catch (TargetWriteException e) {
            return e.isRetryable()
                    ? TaskResult.retry(e.getCode(), e.getMessage(), target.physicalIndex())
                    : TaskResult.failed(e.getCode(), e.getMessage(), target.physicalIndex());
        } catch (IndexWriterException e) {
            // Provider calls: server errors, timeouts and token problems are retryable, configuration problems are not
            return e.isRetryable()
                    ? TaskResult.retry(TaskErrorCode.SOURCE_UNAVAILABLE, e.getMessage(), null)
                    : TaskResult.failed(TaskErrorCode.SOURCE_REQUEST_REJECTED, e.getMessage(), null);
        }
    }

    private Optional<MessageOperationConfig> upsertOperation(JobRequest request) {
        return messageConfigurationRepository.findByName(request.messageName())
                .filter(message -> Objects.equals(message.topicName(), request.topicName()))
                .map(MessageConfig::operations)
                .flatMap(operations -> operations.stream()
                        .filter(op -> op.indexOperation() == IndexOperation.UPSERT && op.indexType().equals(request.indexType()))
                        .findFirst());
    }

    private static TaskResult providerRejection(CurrentSearchItemException e, JobTarget target) {
        TaskErrorCode code = switch (e.getReason()) {
            case NOT_FOUND -> TaskErrorCode.SOURCE_NOT_FOUND;
            case UNSUPPORTED_INDEX_MAJOR_VERSION -> TaskErrorCode.INDEX_MAJOR_VERSION_UNSUPPORTED;
            case REQUEST_REJECTED -> TaskErrorCode.SOURCE_REQUEST_REJECTED;
            case RESPONSE_MISMATCH -> e.getActualIndexMajorVersion() != null && e.getActualIndexMajorVersion() != target.indexMajorVersion()
                    ? TaskErrorCode.INDEX_MAJOR_VERSION_MISMATCH : TaskErrorCode.SCHEMA_MISMATCH;
        };
        return TaskResult.failed(code, e.getMessage() + "; no document was written", null,
                e.getActualIndexMajorVersion(), e.getActualIndexMinorVersion());
    }
}
