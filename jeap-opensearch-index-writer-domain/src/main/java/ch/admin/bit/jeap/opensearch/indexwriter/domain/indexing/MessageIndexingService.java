package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indextype.IndexTypeDescriptor;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageIndexingService {

    private static final String INDEXING_TIME_METRICS_NAME = "jeap.opensearch.indexwriter.indexing";

    private final FeatureManager featureManager;
    private final SearchItemProvider searchItemProvider;
    private final IndexTypeRepository indexTypeRepository;
    private final IndexWriter indexWriter;
    private final MeterRegistry meterRegistry;

    public void index(Message message, MessageOperationConfig operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            indexInternal(message, operation);
        } finally {
            sample.stop(Timer.builder(INDEXING_TIME_METRICS_NAME)
                    .tag("message_type", message.getType().getName())
                    .tag("operation", operation.indexOperation().name())
                    .register(meterRegistry));
        }
    }

    private void indexInternal(Message message, MessageOperationConfig operation) {
        if (!shouldProcessIndexing(message, operation)) {
            return;
        }

        log.debug("Executing {} for index type '{}' triggered by message '{}'",
                operation.indexOperation(), operation.indexType(), message.getType().getName());
        ReferenceProvider<Message> referenceProvider = operation.referenceProvider();
        OriginReference originReference = referenceProvider.extractReference(message);

        SearchItemResult searchItemResult = searchItemProvider.findSearchItem(operation.uri(), operation.indexType(), originReference).orElseThrow(
                () -> IndexingException.searchItemNotFound(operation, originReference)
        );

        IndexTypeDescriptor indexTypeDescriptor = indexTypeRepository.findByOriginTypeAndMajorVersion(operation.indexType(), searchItemResult.indexMajorVersion())
                .orElseThrow(() -> IndexingException.indexTypeNotFound(operation.indexType(), searchItemResult.indexMajorVersion()));

        switch (operation.indexOperation()) {
            case UPSERT -> upsert(indexTypeDescriptor, originReference, searchItemResult);
            case DELETE -> indexWriter.deleteSearchItem(indexTypeDescriptor.indexWriteAlias(), originReference.id());
        }
    }

    private void upsert(IndexTypeDescriptor indexType, OriginReference originReference, SearchItemResult searchItemResult) {
        SearchItemMetadata metadata = new SearchItemMetadata(Instant.now(), searchItemResult.indexMinorVersion());
        SearchItemIndexed<?> searchItemIndexed = searchItemResult.searchItem().withMetadata(metadata);

        indexWriter.upsertSearchItem(indexType.indexWriteAlias(), originReference.id(), searchItemIndexed);
    }

    private boolean shouldProcessIndexing(Message message, MessageOperationConfig operation) {
        if (!isFeatureFlagActive(operation)) {
            log.debug("Skipping {} for index type '{}' — feature flag '{}' is inactive",
                    operation.indexOperation(), operation.indexType(), operation.featureFlag());
            return false;
        }
        if (hasConditionAndIsNotMet(message, operation)) {
            log.debug("Skipping {} for index type '{}' — indexing condition '{}' not met",
                    operation.indexOperation(), operation.indexType(), operation.condition().getClass().getSimpleName());
            return false;
        }
        return true;
    }

    private boolean hasConditionAndIsNotMet(Message message, MessageOperationConfig operation) {
        return operation.condition() != null && !operation.condition().evaluate(message);
    }

    private boolean isFeatureFlagActive(MessageOperationConfig operation) {
        if (operation.featureFlag() != null) {
            boolean active = featureManager.isActive(new NamedFeature(operation.featureFlag()));
            log.debug("FeatureFlag={} indexType={} state={}", operation.featureFlag(), operation.indexType(), active);
            return active;
        }
        return true;
    }
}
