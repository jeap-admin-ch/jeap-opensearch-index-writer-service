package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;

import java.util.Optional;

public interface SearchItemProvider {

    Optional<SearchItemResult> findSearchItem(String baseUri, String indexType, OriginReference originReference);
}
