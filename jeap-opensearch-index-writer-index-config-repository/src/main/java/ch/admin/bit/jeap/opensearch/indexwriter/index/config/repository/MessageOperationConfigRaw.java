package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;

record MessageOperationConfigRaw(
        String indexType,
        IndexOperation indexOperation,
        String uri,
        String oauthClientId,
        String referenceProvider,
        String condition,
        String featureFlag
) {
}
