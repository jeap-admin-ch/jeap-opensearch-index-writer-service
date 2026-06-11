package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemView;
import ch.admin.bit.jeap.opensearch.indextype.Origin;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class SearchItemAuthorization {

    public boolean isAuthorized(SearchItemView searchItem, Authorization auth) {
        Objects.requireNonNull(searchItem, "searchItem must not be null");
        return isAuthorizedByOriginAndRoles(searchItem.origin(), searchItem.indexType().roles(), auth);
    }

    public <S extends SearchItemView> List<S> filterByAuthorization(
            List<S> searchItems, Authorization auth) {
        Objects.requireNonNull(searchItems, "searchItems must not be null");
        return searchItems.stream()
                .filter(item -> isAuthorized(item, auth))
                .toList();
    }

    /**
     * Same as {@link #filterByAuthorization(List, Authorization)} but uses
     * {@code requiredRoles} instead of each item's own {@code indexType().roles()}.
     * Use this when all versions in a multi-version search share authorization via the
     * latest version's roles.
     */
    public <S extends SearchItemView> List<S> filterByAuthorization(
            List<S> searchItems, Authorization auth, List<String> requiredRoles) {
        Objects.requireNonNull(searchItems, "searchItems must not be null");
        return searchItems.stream()
                .filter(item -> isAuthorizedByOriginAndRoles(item.origin(), requiredRoles, auth))
                .toList();
    }

    private boolean isAuthorizedByOriginAndRoles(Origin origin, List<String> roles, Authorization auth) {
        if (auth == null) {
            return false;
        }
        Set<String> requiredRoles = Set.copyOf(roles);
        if (auth.userroles().stream().anyMatch(requiredRoles::contains)) {
            return true;
        }
        String bpId = origin.bpId();
        if (bpId != null) {
            Set<String> bpRoles = auth.bproles().getOrDefault(bpId, Set.of());
            return bpRoles.stream().anyMatch(requiredRoles::contains);
        }
        return false;
    }
}
