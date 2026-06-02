package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;

public record MessageOperationConfig(
        String indexType,
        IndexOperation indexOperation,
        String uri,
        String oauthClientId,
        ReferenceProvider<Message> referenceProvider,
        IndexingCondition<Message> condition,
        String featureFlag
) {
}
