package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.messaging.model.MessageType;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageIndexingServiceTest {

    private static final String BASE_URI = "https://example.com/api";
    private static final String INDEX_TYPE = "PreziusRegistration";
    private static final String INDEX_WRITE_ALIAS = "prezius-registration-write";
    private static final OriginReference ORIGIN_REF = new OriginReference(INDEX_TYPE, "id-1", null);

    @Mock
    private FeatureManager featureManager;

    @Mock
    private IndexWriter indexWriter;

    @Mock
    private SearchItemProvider searchItemProvider;

    @Mock
    private IndexTypeRepository indexTypeRepository;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private MessageIndexingService service;

    @Mock
    private Message message;

    @Mock
    @SuppressWarnings("rawtypes")
    private ReferenceProvider referenceProvider;

    @Mock
    @SuppressWarnings("rawtypes")
    private IndexType indexType;

    @Mock
    @SuppressWarnings("rawtypes")
    private IndexingCondition condition;

    @Test
    @SuppressWarnings("unchecked")
    void upsertFetchesSearchItemResolvesIndexTypeAndCallsWriter() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        stubSearchItemFound();
        stubIndexType();

        assertThatNoException().isThrownBy(() -> service.index(message, operation(IndexOperation.UPSERT, null)));

        verify(indexWriter).upsertSearchItem(eq(INDEX_WRITE_ALIAS), eq("id-1"), any());
        verifyNoMoreInteractions(featureManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteFetchesSearchItemToResolveMajorVersionAndCallsWriter() {
        stubMessageType("PreziusRegistrationDeleted");
        stubReferenceProvider();
        stubSearchItemFound();
        stubIndexType();

        assertThatNoException().isThrownBy(() -> service.index(message, operation(IndexOperation.DELETE, null)));

        verify(searchItemProvider).findSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_REF);
        verify(indexWriter).deleteSearchItem(INDEX_WRITE_ALIAS, "id-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteThrowsWhenSearchItemNotFound() {
        stubMessageType("PreziusRegistrationDeleted");
        stubReferenceProvider();
        when(searchItemProvider.findSearchItem(any(), any(), any())).thenReturn(Optional.empty());
        var op = operation(IndexOperation.DELETE, null);

        assertThatThrownBy(() -> service.index(message, op))
                .isInstanceOf(IndexingException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertThrowsWhenSearchItemNotFound() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        when(searchItemProvider.findSearchItem(any(), any(), any())).thenReturn(Optional.empty());
        var op = operation(IndexOperation.UPSERT, null);

        assertThatThrownBy(() -> service.index(message, op))
                .isInstanceOf(IndexingException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationIsExecutedWhenFeatureFlagIsActive() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        stubSearchItemFound();
        stubIndexType();
        when(featureManager.isActive(argThat(f -> f.name().equals("MY_FLAG")))).thenReturn(true);

        assertThatNoException().isThrownBy(() -> service.index(message, operation(IndexOperation.UPSERT, "MY_FLAG")));

        verify(featureManager).isActive(argThat(f -> f.name().equals("MY_FLAG")));
        verify(indexWriter).upsertSearchItem(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationProceedsWhenConditionIsMet() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        stubSearchItemFound();
        stubIndexType();
        when(condition.evaluate(message)).thenReturn(true);

        assertThatNoException().isThrownBy(() -> service.index(message,
                new MessageOperationConfig(INDEX_TYPE, IndexOperation.UPSERT, BASE_URI, referenceProvider, condition, null)));

        verify(indexWriter).upsertSearchItem(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationIsSkippedWhenConditionIsNotMet() {
        stubMessageType("PreziusRegistrationCreated");
        when(condition.evaluate(message)).thenReturn(false);

        assertThatNoException().isThrownBy(() -> service.index(message,
                new MessageOperationConfig(INDEX_TYPE, IndexOperation.UPSERT, BASE_URI, referenceProvider, condition, null)));

        verifyNoInteractions(searchItemProvider, indexWriter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertThrowsWhenIndexTypeNotFound() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        stubSearchItemFound();
        when(indexTypeRepository.findByOriginTypeAndMajorVersion(INDEX_TYPE, 1)).thenReturn(Optional.empty());
        var op = operation(IndexOperation.UPSERT, null);

        assertThatThrownBy(() -> service.index(message, op))
                .isInstanceOf(IndexingException.class);
    }

    @Test
    void operationIsSkippedWhenFeatureFlagIsInactive() {
        stubMessageType("PreziusRegistrationCreated");
        when(featureManager.isActive(any(NamedFeature.class))).thenReturn(false);

        assertThatNoException().isThrownBy(() -> service.index(message, operation(IndexOperation.UPSERT, "MY_FLAG")));

        verifyNoInteractions(searchItemProvider, indexWriter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationProceedsWithoutCheckingFeatureManagerWhenNoFlagSet() {
        stubMessageType("PreziusRegistrationCreated");
        stubReferenceProvider();
        stubSearchItemFound();
        stubIndexType();

        assertThatNoException().isThrownBy(() -> service.index(message, operation(IndexOperation.UPSERT, null)));

        verifyNoInteractions(featureManager);
    }

    @SuppressWarnings("unchecked")
    private void stubReferenceProvider() {
        when(referenceProvider.extractReference(message)).thenReturn(ORIGIN_REF);
    }

    private void stubSearchItemFound() {
        SearchItemResult result = new SearchItemResult(1, 0,
                new SearchItem<>(null, JsonNodeFactory.instance.objectNode()));
        when(searchItemProvider.findSearchItem(any(), any(), any())).thenReturn(Optional.of(result));
    }

    @SuppressWarnings("unchecked")
    private void stubIndexType() {
        when(indexTypeRepository.findByOriginTypeAndMajorVersion(INDEX_TYPE, 1)).thenReturn(Optional.of(indexType));
        when(indexType.indexWriteAlias()).thenReturn(INDEX_WRITE_ALIAS);
    }

    private void stubMessageType(String typeName) {
        MessageType type = mock(MessageType.class);
        when(message.getType()).thenReturn(type);
        when(type.getName()).thenReturn(typeName);
    }

    @SuppressWarnings("unchecked")
    private MessageOperationConfig operation(IndexOperation op, String featureFlag) {
        return new MessageOperationConfig(INDEX_TYPE, op, BASE_URI, referenceProvider, null, featureFlag);
    }
}
