package ch.admin.bit.jeap.opensearch.client.filter;

import lombok.experimental.UtilityClass;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;

import java.util.List;
import java.util.Set;

@UtilityClass
public class OriginFilter {

    private static final String FIELD_BP_ID = "origin.bp_id";
    private static final String FIELD_TENANT = "origin.tenant";

    public static Query forBusinessPartner(String bpId) {
        requireNonBlank(bpId, "bpId");
        return termQuery(FIELD_BP_ID, bpId);
    }

    public static Query forBusinessPartners(Set<String> bpIds) {
        requireNonEmptySet(bpIds, "bpIds");
        return termsQuery(FIELD_BP_ID, bpIds);
    }

    public static Query forTenant(String tenant) {
        requireNonBlank(tenant, "tenant");
        return termQuery(FIELD_TENANT, tenant);
    }

    public static Query forTenants(Set<String> tenants) {
        requireNonEmptySet(tenants, "tenants");
        return termsQuery(FIELD_TENANT, tenants);
    }

    private static Query termQuery(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value))).toQuery();
    }

    private static Query termsQuery(String field, Set<String> values) {
        List<FieldValue> fieldValues = values.stream()
                .map(FieldValue::of)
                .toList();
        TermsQueryField termsField = TermsQueryField.of(b -> b.value(fieldValues));
        return TermsQuery.of(t -> t.field(field).terms(termsField)).toQuery();
    }

    private static void requireNonBlank(String value, String parameterName) {
        if (value == null) {
            throw new IllegalArgumentException(parameterName + " must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
    }

    private static void requireNonEmptySet(Set<String> values, String parameterName) {
        if (values == null) {
            throw new IllegalArgumentException(parameterName + " must not be null");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(parameterName + " must not be empty");
        }
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException(parameterName + " must not contain null elements");
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException(parameterName + " must not contain blank elements");
            }
        }
    }
}
