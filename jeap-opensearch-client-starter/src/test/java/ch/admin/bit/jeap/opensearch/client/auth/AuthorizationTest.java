package ch.admin.bit.jeap.opensearch.client.auth;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationTest {

    @Test
    void constructor_nullUserroles_throwsNullPointerExceptionMentioningField() {
        Map<String, Set<String>> emptyBpRoles = Map.of();
        assertThatThrownBy(() -> new Authorization(null, emptyBpRoles))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userroles");
    }

    @Test
    void constructor_nullBproles_throwsNullPointerExceptionMentioningField() {
        Set<String> emptyUserRoles = Set.of();
        assertThatThrownBy(() -> new Authorization(emptyUserRoles, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bproles");
    }

    @Test
    void constructor_defensiveCopiesUserroles_mutationOfInputDoesNotLeak() {
        Set<String> mutableUserRoles = new HashSet<>();
        mutableUserRoles.add("role-a");

        Authorization auth = new Authorization(mutableUserRoles, Map.of());
        mutableUserRoles.add("role-b");

        assertThat(auth.userroles()).containsExactly("role-a");
    }

    @Test
    void constructor_defensiveCopiesBprolesMap_mutationOfInputDoesNotLeak() {
        Map<String, Set<String>> mutableBpRoles = new HashMap<>();
        mutableBpRoles.put("bp-1", Set.of("role-a"));

        Authorization auth = new Authorization(Set.of(), mutableBpRoles);
        mutableBpRoles.put("bp-2", Set.of("role-b"));

        assertThat(auth.bproles()).containsOnlyKeys("bp-1");
    }

    @Test
    void constructor_defensiveCopiesInnerBprolesSet_mutationOfInputDoesNotLeak() {
        Set<String> mutableInnerRoles = new HashSet<>();
        mutableInnerRoles.add("role-a");
        Map<String, Set<String>> bpRoles = new HashMap<>();
        bpRoles.put("bp-1", mutableInnerRoles);

        Authorization auth = new Authorization(Set.of(), bpRoles);
        mutableInnerRoles.add("role-b");

        assertThat(auth.bproles().get("bp-1")).containsExactly("role-a");
    }

    @Test
    void constructor_innerBprolesSetIsUnmodifiable() {
        Map<String, Set<String>> bpRoles = new HashMap<>();
        bpRoles.put("bp-1", new HashSet<>(Set.of("role-a")));

        Authorization auth = new Authorization(Set.of(), bpRoles);

        Set<String> storedInner = auth.bproles().get("bp-1");
        assertThatThrownBy(() -> storedInner.add("role-b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    static Stream<Arguments> allRolesCases() {
        return Stream.of(
                Arguments.of(Set.of("u-1", "u-2"), Map.of(), Set.of("u-1", "u-2")),
                Arguments.of(Set.of(), Map.of("bp-1", Set.of("b-1"), "bp-2", Set.of("b-2")),
                        Set.of("b-1", "b-2")),
                Arguments.of(Set.of("u-1"), Map.of("bp-1", Set.of("b-1")), Set.of("u-1", "b-1")),
                // user + bp-roles, with overlap — duplicates collapse
                Arguments.of(Set.of("shared"), Map.of("bp-1", Set.of("shared", "b-1")),
                        Set.of("shared", "b-1")),
                Arguments.of(Set.of("u-1"),
                        Map.of("bp-1", Set.of("b-1", "shared"),
                                "bp-2", Set.of("b-2", "shared")),
                        Set.of("u-1", "b-1", "b-2", "shared")));
    }

    @ParameterizedTest(name = "[{index}] userroles={0}, bproles={1} -> allRoles={2}")
    @MethodSource("allRolesCases")
    void allRoles_returnsUnionOfUserAndBpRoles(
            Set<String> userRoles, Map<String, Set<String>> bpRoles, Set<String> expectedUnion) {
        Authorization auth = new Authorization(userRoles, bpRoles);

        assertThat(auth.allRoles()).containsExactlyInAnyOrderElementsOf(expectedUnion);
    }

    @Test
    void allRoles_emptyInputs_returnsEmpty() {
        Authorization auth = new Authorization(Set.of(), Map.of());

        assertThat(auth.allRoles()).isEmpty();
    }

    @Test
    void allRoles_emptyInnerBpRolesSet_isHandledLikeNoBpRole() {
        Authorization auth = new Authorization(Set.of("u-1"),
                Map.of("bp-empty", Set.of()));

        assertThat(auth.allRoles()).containsExactly("u-1");
    }

    @Nested
    class GetAllBusinessPartnerIdsWithAnyOf {

        @Test
        void nullRoles_throwsNullPointerExceptionMentioningField() {
            Authorization auth = new Authorization(Set.of(), Map.of());

            assertThatThrownBy(() -> auth.getAllBusinessPartnerIdsWithAnyOf(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("roles");
        }

        @Test
        void happyPath_returnsOnlyBpsThatHaveAnyOfTheRoles() {
            Authorization auth = new Authorization(
                    Set.of("u-1"),
                    Map.of(
                            "bp-match-1", Set.of("read", "extra"),
                            "bp-no-match", Set.of("other"),
                            "bp-match-2", Set.of("write")));

            Set<String> result = auth.getAllBusinessPartnerIdsWithAnyOf(Set.of("read", "write"));

            assertThat(result).containsExactlyInAnyOrder("bp-match-1", "bp-match-2");
        }

        @Test
        void noMatch_returnsEmpty() {
            Authorization auth = new Authorization(
                    Set.of("u-1"),
                    Map.of(
                            "bp-1", Set.of("a"),
                            "bp-2", Set.of("b")));

            assertThat(auth.getAllBusinessPartnerIdsWithAnyOf(Set.of("z"))).isEmpty();
        }

        @Test
        void emptyRolesArgument_returnsEmpty() {
            Authorization auth = new Authorization(
                    Set.of("u-1"),
                    Map.of(
                            "bp-1", Set.of("a"),
                            "bp-2", Set.of("b")));

            assertThat(auth.getAllBusinessPartnerIdsWithAnyOf(Set.of())).isEmpty();
        }

        @Test
        void emptyBpRoles_returnsEmpty() {
            Authorization auth = new Authorization(Set.of("u-1"), Map.of());

            assertThat(auth.getAllBusinessPartnerIdsWithAnyOf(Set.of("anything"))).isEmpty();
        }

        @Test
        void userRolesAreNotConsidered_onlyBpRolesMatter() {
            Authorization auth = new Authorization(
                    Set.of("read"),
                    Map.of("bp-1", Set.of("write")));

            assertThat(auth.getAllBusinessPartnerIdsWithAnyOf(Set.of("read"))).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] roles={0} -> bps={1}")
        @MethodSource("ch.admin.bit.jeap.opensearch.client.auth.AuthorizationTest#getAllBusinessPartnerIdsCases")
        void parametrised_returnsExpectedBusinessPartners(
                Set<String> requestedRoles, Set<String> expectedBps) {
            Authorization auth = new Authorization(
                    Set.of(),
                    Map.of(
                            "bp-a", Set.of("read"),
                            "bp-b", Set.of("write"),
                            "bp-c", Set.of("read", "write"),
                            "bp-d", Set.of("admin")));

            assertThat(auth.getAllBusinessPartnerIdsWithAnyOf(requestedRoles))
                    .containsExactlyInAnyOrderElementsOf(expectedBps);
        }
    }

    static Stream<Arguments> getAllBusinessPartnerIdsCases() {
        return Stream.of(
                Arguments.of(Set.of("read"),
                        Set.of("bp-a", "bp-c")),
                Arguments.of(Set.of("write"),
                        Set.of("bp-b", "bp-c")),
                Arguments.of(Set.of("admin"),
                        Set.of("bp-d")),
                Arguments.of(Set.of("read", "write"),
                        Set.of("bp-a", "bp-b", "bp-c")),
                Arguments.of(linkedSet("read", "admin"),
                        Set.of("bp-a", "bp-c", "bp-d")),
                Arguments.of(Set.of("does-not-exist"),
                        Set.of()));
    }

    @SuppressWarnings("SameParameterValue")
    private static Set<String> linkedSet(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    @Nested
    class HasUserroleAnyOf {

        @Test
        void nullRoles_throwsNullPointerExceptionMentioningField() {
            Authorization auth = new Authorization(Set.of("u-1"), Map.of());

            assertThatThrownBy(() -> auth.hasUserroleAnyOf(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("roles");
        }

        @Test
        void matchingRole_returnsTrue() {
            Authorization auth = new Authorization(Set.of("read", "write"), Map.of());

            assertThat(auth.hasUserroleAnyOf(Set.of("read"))).isTrue();
            assertThat(auth.hasUserroleAnyOf(Set.of("write"))).isTrue();
        }

        @Test
        void anyOneMatch_returnsTrue() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(auth.hasUserroleAnyOf(Set.of("write", "read", "admin"))).isTrue();
        }

        @Test
        void noMatch_returnsFalse() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(auth.hasUserroleAnyOf(Set.of("admin", "write"))).isFalse();
        }

        @Test
        void emptyRolesArgument_returnsFalse() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(auth.hasUserroleAnyOf(Set.of())).isFalse();
        }

        @Test
        void emptyUserroles_bpRolesDoNotCount_returnsFalse() {
            Authorization auth = new Authorization(
                    Set.of(),
                    Map.of("bp-1", Set.of("read")));

            assertThat(auth.hasUserroleAnyOf(Set.of("read"))).isFalse();
        }

        @Test
        void bpRolesAreNotConsidered_onlyUserrolesMatter() {
            Authorization auth = new Authorization(
                    Set.of("write"),
                    Map.of("bp-1", Set.of("read")));

            assertThat(auth.hasUserroleAnyOf(Set.of("read"))).isFalse();
            assertThat(auth.hasUserroleAnyOf(Set.of("write"))).isTrue();
        }

        @Test
        void exactStringMatching_caseSensitive() {
            Authorization auth = new Authorization(Set.of("read"), Map.of());

            assertThat(auth.hasUserroleAnyOf(Set.of("READ"))).isFalse();
            assertThat(auth.hasUserroleAnyOf(Set.of("read "))).isFalse();
            assertThat(auth.hasUserroleAnyOf(Set.of("read"))).isTrue();
        }
    }
}
