package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import lombok.Getter;

@Getter
public class SearchItemAccessDeniedException extends RuntimeException {

    private final transient SearchItemTyped<?> searchItem;

    public SearchItemAccessDeniedException(SearchItemTyped<?> searchItem) {
        super("Access denied to search item with origin.id='" + searchItem.origin().id()
                + "' in index type '" + searchItem.indexType().getClass().getSimpleName() + "'.");
        this.searchItem = searchItem;
    }

}
