package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.opensearch.searchitem.api.exception.SearchItemsBadInputException;
import ch.admin.bit.jeap.opensearch.searchitem.model.SearchItemContainer;

import java.util.Optional;

public interface SearchItemsProvider {

    Optional<SearchItemContainer> findSearchItem(String indexType, String originId, String originVersion) throws SearchItemsBadInputException;
}
