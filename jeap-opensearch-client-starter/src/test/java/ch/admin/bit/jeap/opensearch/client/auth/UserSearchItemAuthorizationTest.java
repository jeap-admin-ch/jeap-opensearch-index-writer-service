package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSearchItemAuthorizationTest {

    @Mock
    private UserAuthorizationProvider userAuthorizationProvider;

    @Mock
    private SearchItemAuthorization searchItemAuthorization;

    @InjectMocks
    private UserSearchItemAuthorization sut;

    private static final IndexType<String> INDEX_TYPE_READ =
            new AuthTestData.TestStringIndexType(List.of("read"));

    @Test
    void getUserAuthorization_returnsProviderResult_whenAvailable() {
        Authorization auth = new Authorization(Set.of("read"), Map.of());
        when(userAuthorizationProvider.getAuthorization()).thenReturn(auth);

        Authorization result = sut.getUserAuthorization();

        assertThat(result).isSameAs(auth);
    }

    @Test
    void getUserAuthorization_returnsNull_whenProviderReturnsNull() {
        when(userAuthorizationProvider.getAuthorization()).thenReturn(null);

        Authorization result = sut.getUserAuthorization();

        assertThat(result).isNull();
    }

    @Test
    void isAuthorized_delegatesToSearchItemAuthorization_withProviderResult_andPropagatesTrue() {
        Authorization providerResult = new Authorization(Set.of("read"), Map.of());
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        when(userAuthorizationProvider.getAuthorization()).thenReturn(providerResult);
        when(searchItemAuthorization.isAuthorized(item, providerResult)).thenReturn(true);

        boolean result = sut.isAuthorized(item);

        assertThat(result).isTrue();

        ArgumentCaptor<Authorization> authCaptor = ArgumentCaptor.forClass(Authorization.class);
        verify(searchItemAuthorization).isAuthorized(eq(item), authCaptor.capture());
        assertThat(authCaptor.getValue()).isSameAs(providerResult);
    }

    @Test
    void isAuthorized_propagatesFalse_fromSearchItemAuthorization() {
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        when(userAuthorizationProvider.getAuthorization()).thenReturn(null);
        when(searchItemAuthorization.isAuthorized(item, null)).thenReturn(false);

        boolean result = sut.isAuthorized(item);

        assertThat(result).isFalse();
        verify(searchItemAuthorization).isAuthorized(item, null);
    }

    @Test
    void checkAuthorization_authorized_doesNotThrow() {
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        Authorization providerResult = new Authorization(Set.of("read"), Map.of());
        when(userAuthorizationProvider.getAuthorization()).thenReturn(providerResult);
        when(searchItemAuthorization.isAuthorized(item, providerResult)).thenReturn(true);

        sut.checkAuthorization(item);
    }

    @Test
    void checkAuthorization_notAuthorized_throwsSearchItemAccessDeniedException_carryingItem() {
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        when(userAuthorizationProvider.getAuthorization()).thenReturn(null);
        when(searchItemAuthorization.isAuthorized(item, null)).thenReturn(false);

        assertThatThrownBy(() -> sut.checkAuthorization(item))
                .isInstanceOfSatisfying(SearchItemAccessDeniedException.class,
                        ex -> assertThat(ex.getSearchItem()).isSameAs(item));
    }

    @Test
    void checkAuthorization_authorized_doesNotCallSearchItemAuthorizationCheckAuthorization() {
        // The convenience layer routes through isAuthorized(item) — not through
        // checkAuthorization(item, auth) — and throws locally on a false result.
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        Authorization providerResult = new Authorization(Set.of("read"), Map.of());
        when(userAuthorizationProvider.getAuthorization()).thenReturn(providerResult);
        when(searchItemAuthorization.isAuthorized(item, providerResult)).thenReturn(true);

        sut.checkAuthorization(item);

        verify(searchItemAuthorization).isAuthorized(item, providerResult);
        verify(searchItemAuthorization, never()).checkAuthorization(any(), any());
    }

    @Test
    void filterByAuthorization_delegatesToSearchItemAuthorization_withProviderResult_andReturnsItsResult() {
        Authorization providerResult = new Authorization(Set.of("read"), Map.of());
        SearchItemTyped<String> kept = AuthTestData.searchItemWithId("kept", "bp-1", INDEX_TYPE_READ);
        SearchItemTyped<String> dropped = AuthTestData.searchItemWithId("dropped", "bp-2", INDEX_TYPE_READ);
        List<SearchItemTyped<String>> input = List.of(kept, dropped);
        List<SearchItemTyped<String>> filtered = List.of(kept);

        when(userAuthorizationProvider.getAuthorization()).thenReturn(providerResult);
        when(searchItemAuthorization.filterByAuthorization(input, providerResult)).thenReturn(filtered);

        List<SearchItemTyped<String>> result = sut.filterByAuthorization(input);

        assertThat(result).isSameAs(filtered);

        verify(searchItemAuthorization).filterByAuthorization(input, providerResult);
        verifyNoMoreInteractions(searchItemAuthorization);
    }

    @Test
    void filterByAuthorization_providerReturnsNull_isDelegatedAsNullAuthToDelegate() {
        SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
        List<SearchItemTyped<String>> input = List.of(item);
        when(userAuthorizationProvider.getAuthorization()).thenReturn(null);
        when(searchItemAuthorization.filterByAuthorization(input, null)).thenReturn(List.of());

        List<SearchItemTyped<String>> result = sut.filterByAuthorization(input);

        assertThat(result).isEmpty();
        verify(searchItemAuthorization).filterByAuthorization(input, null);
    }

    @Nested
    class NullProvider {

        private UserSearchItemAuthorization sutWithNullProvider() {
            return new UserSearchItemAuthorization(null, searchItemAuthorization);
        }

        @Test
        void getUserAuthorization_nullProvider_returnsNull() {
            assertThat(sutWithNullProvider().getUserAuthorization()).isNull();
        }

        @Test
        void isAuthorized_nullProvider_delegatesWithNullAuth_andReturnsFalse() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
            when(searchItemAuthorization.isAuthorized(item, null)).thenReturn(false);

            boolean result = sutWithNullProvider().isAuthorized(item);

            assertThat(result).isFalse();
            verify(searchItemAuthorization).isAuthorized(item, null);
        }

        @Test
        void filterByAuthorization_nullProvider_delegatesWithNullAuth_andReturnsItsResult() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
            List<SearchItemTyped<String>> input = List.of(item);
            when(searchItemAuthorization.filterByAuthorization(input, null)).thenReturn(List.of());

            List<SearchItemTyped<String>> result = sutWithNullProvider().filterByAuthorization(input);

            assertThat(result).isEmpty();
            verify(searchItemAuthorization).filterByAuthorization(input, null);
        }

        @Test
        void constructor_nullSearchItemAuthorization_throwsNullPointerException() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new UserSearchItemAuthorization(null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("searchItemAuthorization");
        }
    }
}
