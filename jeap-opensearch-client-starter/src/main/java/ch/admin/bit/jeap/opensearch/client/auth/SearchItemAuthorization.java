package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class SearchItemAuthorization {

    public boolean isAuthorized(SearchItemTyped<?> searchItem, Authorization auth) {
        Objects.requireNonNull(searchItem, "searchItem must not be null");
        if (auth == null) {
            return false;
        }
        Set<String> requiredRoles = Set.copyOf(searchItem.indexType().roles());
        if (auth.userroles().stream().anyMatch(requiredRoles::contains)) {
            return true;
        }
        String bpId = searchItem.origin().bpId();
        if (bpId != null) {
            Set<String> bpRoles = auth.bproles().getOrDefault(bpId, Set.of());
            return bpRoles.stream().anyMatch(requiredRoles::contains);
        }
        return false;
    }

    public void checkAuthorization(SearchItemTyped<?> searchItem, Authorization auth) {
        if (!isAuthorized(searchItem, auth)) {
            throw new SearchItemAccessDeniedException(searchItem);
        }
    }

    public <T> List<SearchItemTyped<T>> filterByAuthorization(
            List<SearchItemTyped<T>> searchItems, Authorization auth) {
        Objects.requireNonNull(searchItems, "searchItems must not be null");
        return searchItems.stream()
                .filter(item -> isAuthorized(item, auth))
                .toList();
    }
}
