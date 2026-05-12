package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Convenience layer that pulls {@link Authorization} from a {@link UserAuthorizationProvider}
 * and delegates item-level checks to {@link SearchItemAuthorization}.
 */
public class UserSearchItemAuthorization {

    @Nullable
    private final UserAuthorizationProvider userAuthorizationProvider;
    private final SearchItemAuthorization searchItemAuthorization;

    public UserSearchItemAuthorization(
            @Nullable UserAuthorizationProvider userAuthorizationProvider,
            SearchItemAuthorization searchItemAuthorization) {
        this.userAuthorizationProvider = userAuthorizationProvider;
        this.searchItemAuthorization = Objects.requireNonNull(
                searchItemAuthorization, "searchItemAuthorization must not be null");
    }

    @Nullable
    public Authorization getUserAuthorization() {
        if (userAuthorizationProvider == null) {
            return null;
        }
        return userAuthorizationProvider.getAuthorization();
    }

    public boolean isAuthorized(SearchItemTyped<?> searchItem) {
        return searchItemAuthorization.isAuthorized(searchItem, getUserAuthorization());
    }

    public void checkAuthorization(SearchItemTyped<?> searchItem) {
        if (!isAuthorized(searchItem)) {
            throw new SearchItemAccessDeniedException(searchItem);
        }
    }

    public <T> List<SearchItemTyped<T>> filterByAuthorization(List<SearchItemTyped<T>> items) {
        return searchItemAuthorization.filterByAuthorization(items, getUserAuthorization());
    }
}
