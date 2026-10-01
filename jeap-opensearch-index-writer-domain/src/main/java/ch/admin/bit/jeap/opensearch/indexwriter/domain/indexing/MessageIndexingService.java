package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageIndexingService {

    private static final String INDEXING_TIME_METRICS_NAME = "jeap.opensearch.indexwriter.indexing";
    private static final String KAFKA_LAG_METRICS_NAME = "jeap.opensearch.indexwriter.kafka.lag";
    private static final String SEARCHITEM_FETCH_TIME_METRICS_NAME = "jeap.opensearch.indexwriter.searchitem.fetch";
    private static final String OPENSEARCH_WRITE_TIME_METRICS_NAME = "jeap.opensearch.indexwriter.opensearch.write";

    private final FeatureManager featureManager;
    private final SearchItemProvider searchItemProvider;
    private final IndexTypeRepository indexTypeRepository;
    private final IndexWriter indexWriter;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;

    public void index(Message message, MessageOperationConfig operation) {
        recordKafkaLag(message);
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

    /**
     * Records the time elapsed between the triggering message's creation (set by the publishing
     * producer) and the start of its processing here, i.e. the Kafka/network transit time, separately
     * from the time spent fetching the SearchItem and writing to OpenSearch.
     */
    private void recordKafkaLag(Message message) {
        if (message.getIdentity() == null) {
            return;
        }
        Instant created = message.getIdentity().getCreated();
        if (created == null) {
            return;
        }
        Timer.builder(KAFKA_LAG_METRICS_NAME)
                .tag("message_type", message.getType().getName())
                .register(meterRegistry)
                .record(Duration.between(created, Instant.now()));
    }

    private void indexInternal(Message message, MessageOperationConfig operation) {
        if (!shouldProcessIndexing(message, operation)) {
            return;
        }

        log.debug("Executing {} for index type '{}' triggered by message '{}'",
                operation.indexOperation(), operation.indexType(), message.getType().getName());
        ReferenceProvider<Message> referenceProvider = operation.referenceProvider();
        List<OriginReference> originReferences = referenceProvider.extractReference(message);

        for (OriginReference originReference : originReferences) {
            indexForReference(originReference, operation);
        }
    }

    private void indexForReference(OriginReference originReference, MessageOperationConfig operation) {
        SearchItemResult searchItemResult = timed(SEARCHITEM_FETCH_TIME_METRICS_NAME, operation,
                () -> searchItemProvider.findSearchItem(operation.uri(), operation.indexType(), originReference, operation.oauthClientId())
                        .orElseThrow(() -> IndexingException.searchItemNotFound(operation, originReference)));

        IndexType<?> indexType = indexTypeRepository.findByOriginTypeAndMajorVersion(operation.indexType(), searchItemResult.indexMajorVersion())
                .orElseThrow(() -> IndexingException.indexTypeNotFound(operation.indexType(), searchItemResult.indexMajorVersion()));

        if (operation.indexOperation() == IndexOperation.UPSERT) {
            upsert(indexType, originReference, searchItemResult, operation);
        } else {
            timedWrite(OPENSEARCH_WRITE_TIME_METRICS_NAME, operation, "delete",
                    () -> indexWriter.deleteSearchItem(indexType.indexWriteAlias(), originReference.id()));
        }
    }

    private void upsert(IndexType<?> indexType, OriginReference originReference, SearchItemResult searchItemResult, MessageOperationConfig operation) {
        SearchItemMetadata metadata = new SearchItemMetadata(Instant.now(), searchItemResult.indexMajorVersion(), searchItemResult.indexMinorVersion());
        SearchItemIndexed<?> searchItemIndexed = toSearchItemIndexed(searchItemResult, indexType, metadata);

        validateRequiredFields(indexType.indexWriteAlias(), originReference.id(), searchItemIndexed);
        timedWrite(OPENSEARCH_WRITE_TIME_METRICS_NAME, operation, "upsert",
                () -> indexWriter.upsertSearchItem(indexType.indexWriteAlias(), originReference.id(), searchItemIndexed));
    }

    /**
     * Times a single indexing sub-step (SearchItem fetch) separately from the overall {@code indexing}
     * timer, so the share of time spent in each can be analysed independently (e.g. during load/performance
     * testing).
     */
    private <T> T timed(String metricsName, MessageOperationConfig operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return action.get();
        } finally {
            sample.stop(Timer.builder(metricsName)
                    .tag("index_type", operation.indexType())
                    .register(meterRegistry));
        }
    }

    /**
     * Times an OpenSearch write sub-step, tagged additionally with the write {@code operation} (upsert or
     * delete) so the two can be analysed separately (e.g. during load/performance testing).
     */
    private void timedWrite(String metricsName, MessageOperationConfig operation, String writeOperation, Runnable action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            action.run();
        } finally {
            sample.stop(Timer.builder(metricsName)
                    .tag("index_type", operation.indexType())
                    .tag("operation", writeOperation)
                    .register(meterRegistry));
        }
    }

    /**
     * Converts the raw {@link SearchItemResult} to a {@link SearchItemIndexed} by deserializing the data
     * into the typed {@code dataClass} defined by the {@link IndexType}.
     * <p>
     * The raw JSON data is deserialized into the typed instance using Jackson annotations on the data class
     * (e.g. {@code @JsonProperty}), so field names in OpenSearch match the annotation-defined names.
     *
     * @throws IndexingException if the raw JSON data cannot be deserialized into the typed data class
     */
    private <T> SearchItemIndexed<T> toSearchItemIndexed(SearchItemResult result, IndexType<T> indexType, SearchItemMetadata metadata) {
        Class<T> dataClass = indexType.dataClass();
        try {
            T typedData = jsonMapper.convertValue(result.searchItem().data(), dataClass);
            return new SearchItem<>(result.searchItem().origin(), typedData).withMetadata(metadata);
        } catch (IllegalArgumentException | JacksonException e) {
            throw IndexingException.dataDeserializationFailed(dataClass, indexType.originType(), e);
        }
    }

    private void validateRequiredFields(String indexWriteAlias, String documentId, SearchItemIndexed<?> searchItem) {
        List<String> missing = new ArrayList<>();
        if (searchItem.origin() == null) {
            missing.add("origin");
        } else {
            var origin = searchItem.origin();
            if (origin.id() == null) {
                missing.add("origin.id");
            }
        }
        if (!missing.isEmpty()) {
            throw IndexingException.missingRequiredFields(indexWriteAlias, documentId, missing);
        }
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
