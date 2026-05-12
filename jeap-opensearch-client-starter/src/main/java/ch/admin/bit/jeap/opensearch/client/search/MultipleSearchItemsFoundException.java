package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.util.List;

public class MultipleSearchItemsFoundException extends SearchItemClientException {

    public MultipleSearchItemsFoundException(IndexType<?> indexType, List<String> indices,
                                             String id, int hitCount) {
        super("Read for origin.id='" + id + "' in index type '" + indexType.getClass().getSimpleName()
                + "' on indices " + indices + " returned " + hitCount
                + " hits; exactly one was expected.");
    }
}
