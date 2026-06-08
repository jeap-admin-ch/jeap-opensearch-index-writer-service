package ch.admin.bit.jeap.opensearch.client.auth;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSearchItemAuthorizationTest {

    @Mock
    private UserAuthorizationProvider userAuthorizationProvider;

    @Mock
    private SearchItemAuthorization searchItemAuthorization;

    @Test
    void getUserAuthorization_returnsProviderResult_whenAvailable() {
        Authorization auth = new Authorization(Set.of("read"), Map.of());
        when(userAuthorizationProvider.getAuthorization()).thenReturn(auth);

        Authorization result = new UserSearchItemAuthorization(userAuthorizationProvider, searchItemAuthorization)
                .getUserAuthorization();

        assertThat(result).isSameAs(auth);
    }

    @Test
    void getUserAuthorization_returnsNull_whenProviderReturnsNull() {
        when(userAuthorizationProvider.getAuthorization()).thenReturn(null);

        Authorization result = new UserSearchItemAuthorization(userAuthorizationProvider, searchItemAuthorization)
                .getUserAuthorization();

        assertThat(result).isNull();
    }

    @Nested
    class NullProvider {

        @Test
        void getUserAuthorization_nullProvider_returnsNull() {
            assertThat(new UserSearchItemAuthorization(null, searchItemAuthorization)
                    .getUserAuthorization()).isNull();
        }

        @Test
        void constructor_nullSearchItemAuthorization_throwsNullPointerException() {
            assertThatThrownBy(() -> new UserSearchItemAuthorization(null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("searchItemAuthorization");
        }
    }
}
