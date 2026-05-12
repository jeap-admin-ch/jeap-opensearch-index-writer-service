package ch.admin.bit.jeap.opensearch.client.filter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginFilterTest {

    private static final String FIELD_BP_ID = "origin.bp_id";
    private static final String FIELD_TENANT = "origin.tenant";

    @Nested
    class ForBusinessPartner {

        @Test
        void happyPath_returnsTermQueryOnOriginBpId() {
            Query query = OriginFilter.forBusinessPartner("bp-42");

            assertThat(query.isTerm()).isTrue();
            TermQuery termQuery = query.term();
            assertThat(termQuery.field()).isEqualTo(FIELD_BP_ID);
            FieldValue value = termQuery.value();
            assertThat(value.isString()).isTrue();
            assertThat(value.stringValue()).isEqualTo("bp-42");
        }

        @Test
        void nullArgument_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> OriginFilter.forBusinessPartner(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @ParameterizedTest(name = "[{index}] blank value \"{0}\" -> IllegalArgumentException")
        @ValueSource(strings = {"", " ", "  ", "\t", "\n"})
        void blankArgument_throwsIllegalArgumentException(String blank) {
            assertThatThrownBy(() -> OriginFilter.forBusinessPartner(blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ForBusinessPartners {

        @Test
        void happyPath_returnsTermsQueryOnOriginBpId() {
            Set<String> bpIds = new LinkedHashSet<>();
            bpIds.add("bp-1");
            bpIds.add("bp-2");
            bpIds.add("bp-3");

            Query query = OriginFilter.forBusinessPartners(bpIds);

            assertThat(query.isTerms()).isTrue();
            TermsQuery termsQuery = query.terms();
            assertThat(termsQuery.field()).isEqualTo(FIELD_BP_ID);
            TermsQueryField termsField = termsQuery.terms();
            assertThat(termsField.isValue()).isTrue();
            Set<String> stringValues = termsField.value().stream()
                    .peek(fv -> assertThat(fv.isString()).isTrue())
                    .map(FieldValue::stringValue)
                    .collect(Collectors.toSet());
            assertThat(stringValues).containsExactlyInAnyOrder("bp-1", "bp-2", "bp-3");
        }

        @Test
        void happyPath_singleElementSet_isStillTermsQuery() {
            // forBusinessPartners(Set) is unconditionally a terms-query, even for a single element.
            Query query = OriginFilter.forBusinessPartners(Set.of("bp-only"));

            assertThat(query.isTerms()).isTrue();
            assertThat(query.terms().field()).isEqualTo(FIELD_BP_ID);
            assertThat(query.terms().terms().value())
                    .extracting(FieldValue::stringValue)
                    .containsExactly("bp-only");
        }

        @Test
        void nullArgument_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> OriginFilter.forBusinessPartners(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        void emptySet_throwsIllegalArgumentException() {
            Set<String> emptySet = Collections.emptySet();
            assertThatThrownBy(() -> OriginFilter.forBusinessPartners(emptySet))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void setWithNullElement_throwsIllegalArgumentException() {
            Set<String> bpIds = new HashSet<>();
            bpIds.add("bp-1");
            bpIds.add(null);
            assertThatThrownBy(() -> OriginFilter.forBusinessPartners(bpIds))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "[{index}] set containing blank value \"{0}\" -> IllegalArgumentException")
        @ValueSource(strings = {"", " ", "  ", "\t"})
        void setWithBlankElement_throwsIllegalArgumentException(String blank) {
            Set<String> bpIds = new LinkedHashSet<>();
            bpIds.add("bp-1");
            bpIds.add(blank);
            assertThatThrownBy(() -> OriginFilter.forBusinessPartners(bpIds))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ForTenant {

        @Test
        void happyPath_returnsTermQueryOnOriginTenant() {
            Query query = OriginFilter.forTenant("tenant-zh");

            assertThat(query.isTerm()).isTrue();
            TermQuery termQuery = query.term();
            assertThat(termQuery.field()).isEqualTo(FIELD_TENANT);
            FieldValue value = termQuery.value();
            assertThat(value.isString()).isTrue();
            assertThat(value.stringValue()).isEqualTo("tenant-zh");
        }

        @Test
        void nullArgument_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> OriginFilter.forTenant(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @ParameterizedTest(name = "[{index}] blank value \"{0}\" -> IllegalArgumentException")
        @ValueSource(strings = {"", " ", "  ", "\t", "\n"})
        void blankArgument_throwsIllegalArgumentException(String blank) {
            assertThatThrownBy(() -> OriginFilter.forTenant(blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ForTenants {

        @Test
        void happyPath_returnsTermsQueryOnOriginTenant() {
            Set<String> tenants = new LinkedHashSet<>();
            tenants.add("tenant-zh");
            tenants.add("tenant-be");

            Query query = OriginFilter.forTenants(tenants);

            assertThat(query.isTerms()).isTrue();
            TermsQuery termsQuery = query.terms();
            assertThat(termsQuery.field()).isEqualTo(FIELD_TENANT);
            TermsQueryField termsField = termsQuery.terms();
            assertThat(termsField.isValue()).isTrue();
            Set<String> stringValues = termsField.value().stream()
                    .peek(fv -> assertThat(fv.isString()).isTrue())
                    .map(FieldValue::stringValue)
                    .collect(Collectors.toSet());
            assertThat(stringValues).containsExactlyInAnyOrder("tenant-zh", "tenant-be");
        }

        @Test
        void nullArgument_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> OriginFilter.forTenants(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        void emptySet_throwsIllegalArgumentException() {
            Set<String> emptySet = Collections.emptySet();
            assertThatThrownBy(() -> OriginFilter.forTenants(emptySet))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void setWithNullElement_throwsIllegalArgumentException() {
            Set<String> tenants = new HashSet<>();
            tenants.add("tenant-zh");
            tenants.add(null);
            assertThatThrownBy(() -> OriginFilter.forTenants(tenants))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "[{index}] set containing blank value \"{0}\" -> IllegalArgumentException")
        @ValueSource(strings = {"", " ", "  ", "\t"})
        void setWithBlankElement_throwsIllegalArgumentException(String blank) {
            Set<String> tenants = new LinkedHashSet<>();
            tenants.add("tenant-zh");
            tenants.add(blank);
            assertThatThrownBy(() -> OriginFilter.forTenants(tenants))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
