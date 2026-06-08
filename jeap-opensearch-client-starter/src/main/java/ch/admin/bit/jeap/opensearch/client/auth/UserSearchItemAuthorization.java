package ch.admin.bit.jeap.opensearch.client.auth;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * Convenience layer that pulls {@link Authorization} from a {@link UserAuthorizationProvider}.
 */
public class UserSearchItemAuthorization {

    @Nullable
    private final UserAuthorizationProvider userAuthorizationProvider;

    public UserSearchItemAuthorization(
            @Nullable UserAuthorizationProvider userAuthorizationProvider,
            SearchItemAuthorization searchItemAuthorization) {
        this.userAuthorizationProvider = userAuthorizationProvider;
        Objects.requireNonNull(searchItemAuthorization, "searchItemAuthorization must not be null");
    }

    @Nullable
    public Authorization getUserAuthorization() {
        if (userAuthorizationProvider == null) {
            return null;
        }
        return userAuthorizationProvider.getAuthorization();
    }
}
