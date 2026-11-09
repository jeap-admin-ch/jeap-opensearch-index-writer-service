package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;

import java.util.Optional;

public interface SearchItemProvider {

    Optional<SearchItemResult> findSearchItem(String baseUri, String indexType, OriginReference originReference, String oauthClientId);

    default Optional<SearchItemResult> findCurrentSearchItem(String baseUri, String indexType, String originId, int indexMajorVersion, String oauthClientId) {
        throw new UnsupportedOperationException("%s does not support fetching the current search item".formatted(getClass().getName()));
    }
}
