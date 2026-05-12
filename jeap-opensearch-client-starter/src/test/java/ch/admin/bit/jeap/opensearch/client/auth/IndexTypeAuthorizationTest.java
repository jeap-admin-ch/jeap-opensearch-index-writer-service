package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexTypeAuthorizationTest {

    private final IndexTypeAuthorization sut = new IndexTypeAuthorization();

    @Test
    void canAccess_nullIndexType_throwsNullPointerException() {
        Authorization auth = new Authorization(Set.of("role-a"), Map.of());

        assertThatThrownBy(() -> sut.canAccess(null, auth))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("indexType");
    }

    @Test
    void canAccess_nullAuthorization_returnsFalse() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("role-a"));

        assertThat(sut.canAccess(indexType, null)).isFalse();
    }

    @Test
    void canAccess_singleMatchingUserRole_returnsTrue() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of("read"), Map.of());

        assertThat(sut.canAccess(indexType, auth)).isTrue();
    }

    @Test
    void canAccess_matchingBpRole_returnsTrue() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of(), Map.of("BP1", Set.of("read")));

        assertThat(sut.canAccess(indexType, auth)).isTrue();
    }

    @Test
    void canAccess_noMatchingRole_returnsFalse() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read", "admin"));
        Authorization auth = new Authorization(Set.of("write"), Map.of("BP1", Set.of("other")));

        assertThat(sut.canAccess(indexType, auth)).isFalse();
    }

    @Test
    void canAccess_emptyIndexTypeRoles_returnsFalse() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of());
        Authorization auth = new Authorization(Set.of("read"), Map.of());

        assertThat(sut.canAccess(indexType, auth)).isFalse();
    }

    @Test
    void canAccess_emptyAuthorization_returnsFalse() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of(), Map.of());

        assertThat(sut.canAccess(indexType, auth)).isFalse();
    }

    static Stream<Arguments> roleCombinations() {
        return Stream.of(
                Arguments.of(List.of("read", "write"), Set.of("read"), true),
                Arguments.of(List.of("read", "write"), Set.of("write"), true),
                Arguments.of(List.of("read", "write"), Set.of("admin"), false),
                Arguments.of(List.of("read"), Set.of("read", "extra"), true),
                // case sensitive
                Arguments.of(List.of("read"), Set.of("Read"), false),
                Arguments.of(List.of("a", "b", "c"), Set.of("x", "y", "b"), true),
                Arguments.of(List.of("a", "b", "c"), Set.of("x", "y", "z"), false));
    }

    @ParameterizedTest(name = "[{index}] indexRoles={0} userRoles={1} -> canAccess={2}")
    @MethodSource("roleCombinations")
    void canAccess_parametrised_returnsExpected(
            List<String> indexRoles, Set<String> userRoles, boolean expected) {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(indexRoles);
        Authorization auth = new Authorization(userRoles, Map.of());

        assertThat(sut.canAccess(indexType, auth)).isEqualTo(expected);
    }

    @Test
    void checkAccess_match_doesNotThrow() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of("read"), Map.of());

        sut.checkAccess(indexType, auth);
    }

    @Test
    void checkAccess_nullIndexType_throwsNullPointerException() {
        Authorization auth = new Authorization(Set.of("read"), Map.of());

        assertThatThrownBy(() -> sut.checkAccess(null, auth))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("indexType");
    }

    @Test
    void checkAccess_nullAuthorization_throwsNoAuthorization() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));

        assertThatThrownBy(() -> sut.checkAccess(indexType, null))
                .isInstanceOfSatisfying(IndexTypeAccessDeniedException.class, ex -> {
                    assertThat(ex.getIndexType()).isSameAs(indexType);
                    assertThat(ex.getMessage()).contains("no authorization");
                });
    }

    @Test
    void checkAccess_mismatch_throwsIndexTypeAccessDeniedException() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of("write"), Map.of());

        assertThatThrownBy(() -> sut.checkAccess(indexType, auth))
                .isInstanceOf(IndexTypeAccessDeniedException.class);
    }

    @Test
    void checkAccess_mismatch_exceptionCarriesIndexType() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("read"));
        Authorization auth = new Authorization(Set.of("write"), Map.of());

        try {
            sut.checkAccess(indexType, auth);
            org.assertj.core.api.Assertions.fail("expected IndexTypeAccessDeniedException");
        } catch (IndexTypeAccessDeniedException ex) {
            assertThat(ex.getIndexType()).isSameAs(indexType);
        }
    }

    @Test
    void checkAccess_mismatch_exceptionMessageMentionsIndexType() {
        IndexType<String> indexType = new AuthTestData.TestStringIndexType(List.of("required-role"));
        Authorization auth = new Authorization(Set.of("user-role"), Map.of());

        assertThatThrownBy(() -> sut.checkAccess(indexType, auth))
                .isInstanceOf(IndexTypeAccessDeniedException.class)
                .hasMessageContaining(indexType.getClass().getSimpleName());
    }
}
