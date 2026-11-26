package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemFetcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.IndexingException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Completed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskResult.Retry;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.togglz.core.Feature;
import org.togglz.core.manager.FeatureManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UpsertTaskExecutorTest {

    private static final String TARGET = "index_v1-000001";

    private final MessageConfigurationRepository messages = mock(MessageConfigurationRepository.class);
    private final IndexTypeRepository indexTypes = mock(IndexTypeRepository.class);
    private final FeatureManager featureManager = mock(FeatureManager.class);
    private final CurrentSearchItemFetcher fetcher = mock(CurrentSearchItemFetcher.class);
    private final TargetIndexGuard targetGuard = mock(TargetIndexGuard.class);
    private final MaintenanceTargetWriter writer = mock(MaintenanceTargetWriter.class);
    private final UpsertTaskExecutor executor = new UpsertTaskExecutor(messages, indexTypes, featureManager, fetcher, targetGuard, writer);
    private final IndexType<?> indexType = mock(IndexType.class);
    private final Job job = InMemoryJobStorage.job(UUID.randomUUID(), InMemoryJobStorage.upsert("a"), JobState.OPEN,
            MaterializationState.MATERIALIZED, Instant.now(), null);
    private final JobTask task = JobTask.open(job.jobId(), 0, "a", "v-a", Instant.now());

    @BeforeEach
    void setUp() {
        configureOperation(null);
        doReturn(Optional.of(indexType)).when(indexTypes).findByOriginTypeAndMajorVersion("DecreeDocument", 1);
    }

    @Test
    void execute_replacesTheDocumentInTheStoredTargetWithTheCurrentData() {
        SearchItemIndexed<?> item = item(1, 3);
        doReturn(item).when(fetcher).fetch("http://provider", null, indexType, "a");

        Completed result = (Completed) executor.execute(job, task);

        assertThat(result.state()).isEqualTo(TaskState.SUCCEEDED);
        assertThat(result.actualIndexMajorVersion()).isEqualTo(1);
        assertThat(result.actualIndexMinorVersion()).isEqualTo(3);
        verify(writer).upsert(TARGET, "a", item);
    }

    @Test
    void execute_switchedOffIndexingFlagEndsTheTaskWithoutCallingTheProvider() {
        configureOperation("MY_FLAG");
        when(featureManager.isActive(any(Feature.class))).thenReturn(false);

        Completed result = (Completed) executor.execute(job, task);

        assertThat(result.state()).isEqualTo(TaskState.INDEXING_DISABLED);
        verifyNoInteractions(fetcher, writer);
    }

    @Test
    void execute_switchedOnIndexingFlagRunsTheTask() {
        configureOperation("MY_FLAG");
        when(featureManager.isActive(any(Feature.class))).thenReturn(true);
        doReturn(item(1, 0)).when(fetcher).fetch(anyString(), any(), any(), anyString());

        assertThat(((Completed) executor.execute(job, task)).state()).isEqualTo(TaskState.SUCCEEDED);
    }

    @Test
    void execute_failsIfTheOperationOrIndexTypeIsNoLongerConfigured() {
        when(messages.findByName("Msg")).thenReturn(Optional.empty());
        assertFailed(executor.execute(job, task), TaskErrorCode.CONFIGURATION_MISSING);

        configureOperation(null);
        doReturn(Optional.empty()).when(indexTypes).findByOriginTypeAndMajorVersion("DecreeDocument", 1);
        assertFailed(executor.execute(job, task), TaskErrorCode.INDEX_TYPE_NOT_CONFIGURED);
    }

    @Test
    void execute_mapsProviderRejections() {
        when(fetcher.fetch(anyString(), any(), any(), anyString()))
                .thenThrow(CurrentSearchItemException.notFound("DecreeDocument", 1, "a"))
                .thenThrow(CurrentSearchItemException.unsupportedIndexMajorVersion("DecreeDocument", 1, "a"))
                .thenThrow(CurrentSearchItemException.mismatch("DecreeDocument", 1, "a", "major 2", 2, 0, null))
                .thenThrow(CurrentSearchItemException.mismatch("DecreeDocument", 1, "a", "unknown field", 1, 4, null));

        assertFailed(executor.execute(job, task), TaskErrorCode.SOURCE_NOT_FOUND);
        assertFailed(executor.execute(job, task), TaskErrorCode.INDEX_MAJOR_VERSION_UNSUPPORTED);
        Completed majorMismatch = assertFailed(executor.execute(job, task), TaskErrorCode.INDEX_MAJOR_VERSION_MISMATCH);
        assertThat(majorMismatch.actualIndexMajorVersion()).isEqualTo(2);
        Completed schemaMismatch = assertFailed(executor.execute(job, task), TaskErrorCode.SCHEMA_MISMATCH);
        assertThat(schemaMismatch.actualIndexMinorVersion()).isEqualTo(4);
        verifyNoInteractions(writer);
    }

    @Test
    void execute_transientProviderErrorIsRetried() {
        when(fetcher.fetch(anyString(), any(), any(), anyString()))
                .thenThrow(IndexingException.searchItemProviderServerError("http://provider", "DecreeDocument", 503));

        Retry result = (Retry) executor.execute(job, task);

        assertThat(result.error().code()).isEqualTo("SOURCE_UNAVAILABLE");
    }

    @Test
    void execute_missingTargetFailsBeforeTheProviderIsCalled() {
        doThrow(TargetWriteException.permanent(TaskErrorCode.TARGET_INDEX_MISSING, "gone", null)).when(targetGuard).requireTarget(job);

        Completed result = assertFailed(executor.execute(job, task), TaskErrorCode.TARGET_INDEX_MISSING);

        assertThat(result.error().targetIndex()).isEqualTo(TARGET);
        verifyNoInteractions(fetcher, writer);
    }

    @Test
    void execute_writeFailuresAreRetriedOrFailedDependingOnTheirKind() {
        doReturn(item(1, 0)).when(fetcher).fetch(anyString(), any(), any(), anyString());
        doThrow(TargetWriteException.unavailable("HTTP 503", null))
                .doThrow(TargetWriteException.permanent(TaskErrorCode.INDEX_NOT_WRITABLE, "write block", null))
                .when(writer).upsert(eq(TARGET), eq("a"), any());

        assertThat(((Retry) executor.execute(job, task)).error().code()).isEqualTo("TARGET_UNAVAILABLE");
        assertFailed(executor.execute(job, task), TaskErrorCode.INDEX_NOT_WRITABLE);
    }

    private void configureOperation(String featureFlag) {
        MessageOperationConfig operation = new MessageOperationConfig("DecreeDocument", IndexOperation.UPSERT, "http://provider",
                null, null, null, featureFlag);
        MessageOperationConfig unrelated = new MessageOperationConfig("DecreeDocument", IndexOperation.DELETE, "http://other",
                null, null, null, null);
        when(messages.findByName("Msg")).thenReturn(Optional.of(new MessageConfig("Msg", "topic", List.of(unrelated, operation))));
    }

    private static SearchItemIndexed<?> item(int major, int minor) {
        Origin origin = new Origin("a", null, null, null, null, null, Map.of());
        return new SearchItem<>(origin, "data").withMetadata(new SearchItemMetadata(Instant.now(), major, minor));
    }

    private static Completed assertFailed(TaskResult result, TaskErrorCode code) {
        assertThat(result).isInstanceOf(Completed.class);
        Completed completed = (Completed) result;
        assertThat(completed.state()).isEqualTo(TaskState.FAILED);
        assertThat(completed.error().code()).isEqualTo(code.name());
        return completed;
    }
}
