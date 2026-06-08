package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchItemAuthorizationTest {

    private final SearchItemAuthorization sut = new SearchItemAuthorization();

    private static final IndexType<String> INDEX_TYPE_READ =
            new AuthTestData.TestStringIndexType(List.of("read"));

    @Nested
    class IsAuthorized {

        @Test
        void nullSearchItem_throwsNullPointerException() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThatThrownBy(() -> sut.isAuthorized(null, auth))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("searchItem");
        }

        @Test
        void nullAuth_returnsFalse() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);

            assertThat(sut.isAuthorized(item, null)).isFalse();
        }

        @Test
        void userRoleMatchesRequired_returnsTrue_regardlessOfItemBpId() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-irrelevant", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(sut.isAuthorized(item, auth)).isTrue();
        }

        @Test
        void userRoleMatchesRequired_returnsTrue_evenIfBpIdIsNull() {
            SearchItemTyped<String> item = AuthTestData.searchItem(null, INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(sut.isAuthorized(item, auth)).isTrue();
        }

        @Test
        void bpRoleMatchesRequired_andBpIdMatchesAuthBp_returnsTrue() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of(),
                    Map.of("bp-1", Set.of("read")));

            assertThat(sut.isAuthorized(item, auth)).isTrue();
        }

        @Test
        void bpRoleMatchesRequired_butBpIdDoesNotMatchAuthBp_returnsFalse() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-other", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of(),
                    Map.of("bp-1", Set.of("read")));

            assertThat(sut.isAuthorized(item, auth)).isFalse();
        }

        @Test
        void noUserRoleMatch_andBpIdIsNull_returnsFalse() {
            // bpId == null means the item is not attributable to a single business partner,
            // so bp-roles cannot establish access.
            SearchItemTyped<String> item = AuthTestData.searchItem(null, INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of("other"),
                    Map.of("bp-1", Set.of("read")));

            assertThat(sut.isAuthorized(item, auth)).isFalse();
        }

        @Test
        void neitherUserNorBpRoleMatches_returnsFalse() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of("other"),
                    Map.of("bp-1", Set.of("write")));

            assertThat(sut.isAuthorized(item, auth)).isFalse();
        }

        @Test
        void bpIdHasNoEntryInBpRoles_returnsFalse() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-unknown", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of(),
                    Map.of("bp-1", Set.of("read")));

            assertThat(sut.isAuthorized(item, auth)).isFalse();
        }
    }

    @Nested
    class FilterByAuthorization {

        @Test
        void nullList_throwsNullPointerException() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThatThrownBy(() -> sut.filterByAuthorization(null, auth))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("searchItems");
        }

        @Test
        void mixedList_filtersOutNonAuthorizedItems_andPreservesOrder_bpOnly() {
            SearchItemTyped<String> deniedDifferentBp = AuthTestData.searchItemWithId("id-1", "bp-other", INDEX_TYPE_READ);
            SearchItemTyped<String> allowedByBpRole = AuthTestData.searchItemWithId("id-2", "bp-allowed", INDEX_TYPE_READ);
            SearchItemTyped<String> deniedNoBpAndNoUserRoleMatch = AuthTestData.searchItemWithId("id-3", null, INDEX_TYPE_READ);
            SearchItemTyped<String> allowedByBpRoleAgain = AuthTestData.searchItemWithId("id-4", "bp-allowed", INDEX_TYPE_READ);

            Authorization auth = new Authorization(
                    Set.of(),
                    Map.of("bp-allowed", Set.of("read")));

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(
                    List.of(deniedDifferentBp, allowedByBpRole, deniedNoBpAndNoUserRoleMatch, allowedByBpRoleAgain),
                    auth);

            assertThat(result).containsExactly(allowedByBpRole, allowedByBpRoleAgain);
        }

        @Test
        void mixedList_userRoleGrantsAccessGlobally_evenForUnknownBp_preservesOrder() {
            SearchItemTyped<String> a = AuthTestData.searchItemWithId("a", "bp-1", INDEX_TYPE_READ);
            SearchItemTyped<String> b = AuthTestData.searchItemWithId("b", "bp-2", INDEX_TYPE_READ);
            SearchItemTyped<String> c = AuthTestData.searchItemWithId("c", null, INDEX_TYPE_READ);

            Authorization auth = new Authorization(Set.of("read"), Map.of());

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(List.of(a, b, c), auth);

            assertThat(result).containsExactly(a, b, c);
        }

        @Test
        void filterPreservesInputOrder_evenWhenManyItemsAreDropped() {
            SearchItemTyped<String> kept1 = AuthTestData.searchItemWithId("k1", "bp-allowed", INDEX_TYPE_READ);
            SearchItemTyped<String> dropped1 = AuthTestData.searchItemWithId("d1", "bp-other", INDEX_TYPE_READ);
            SearchItemTyped<String> dropped2 = AuthTestData.searchItemWithId("d2", "bp-yet-other", INDEX_TYPE_READ);
            SearchItemTyped<String> kept2 = AuthTestData.searchItemWithId("k2", "bp-allowed", INDEX_TYPE_READ);
            SearchItemTyped<String> dropped3 = AuthTestData.searchItemWithId("d3", null, INDEX_TYPE_READ);

            Authorization auth = new Authorization(Set.of(),
                    Map.of("bp-allowed", Set.of("read")));

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(
                    List.of(dropped1, kept1, dropped2, kept2, dropped3),
                    auth);

            assertThat(result).containsExactly(kept1, kept2);
        }

        @Test
        void emptyList_returnsEmptyList() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(List.of(), auth);

            assertThat(result).isEmpty();
        }

        @Test
        void nullAuthorization_filtersAllItemsOut_doesNotThrow() {
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(List.of(item), null);

            assertThat(result).isEmpty();
        }

        @Test
        void allItemsAllowed_listIsReturnedInOriginalOrder() {
            SearchItemTyped<String> a = AuthTestData.searchItemWithId("a", "bp-1", INDEX_TYPE_READ);
            SearchItemTyped<String> b = AuthTestData.searchItemWithId("b", "bp-2", INDEX_TYPE_READ);
            SearchItemTyped<String> c = AuthTestData.searchItemWithId("c", "bp-3", INDEX_TYPE_READ);

            Authorization auth = new Authorization(Set.of("read"), Map.of());

            List<SearchItemTyped<String>> result = sut.filterByAuthorization(List.of(a, b, c), auth);

            assertThat(result).containsExactly(a, b, c);
        }
    }

    @Nested
    class FilterByAuthorizationWithExplicitRoles {

        @Test
        void usesProvidedRolesInsteadOfItemIndexTypeRoles() {
            // The item's indexType has role "read", but we pass "other_role" as required
            SearchItemTyped<String> item = AuthTestData.searchItem("bp-1", INDEX_TYPE_READ);
            Authorization auth = new Authorization(Set.of("other_role"), Map.of());

            // With item's own roles ("read"), auth would be denied; with "other_role", it is allowed
            assertThat(sut.filterByAuthorization(List.of(item), auth)).isEmpty();
            assertThat(sut.filterByAuthorization(List.of(item), auth, List.of("other_role")))
                    .containsExactly(item);
        }

        @Test
        void latestVersionRolesUsed_olderVersionItemStillAllowed() {
            // Simulate a v1 item whose indexType has role "v1_role",
            // but we use the latest version's role "v2_role" for all auth decisions
            IndexType<String> v1Type = new AuthTestData.TestStringIndexType(List.of("v1_role"));
            SearchItemTyped<String> v1Item = AuthTestData.searchItemWithId("id-1", "bp-1", v1Type);
            Authorization auth = new Authorization(Set.of("v2_role"), Map.of());

            // Using item's own roles ("v1_role") → denied; using latest ("v2_role") → allowed
            assertThat(sut.filterByAuthorization(List.of(v1Item), auth)).isEmpty();
            assertThat(sut.filterByAuthorization(List.of(v1Item), auth, List.of("v2_role")))
                    .containsExactly(v1Item);
        }
    }
}
