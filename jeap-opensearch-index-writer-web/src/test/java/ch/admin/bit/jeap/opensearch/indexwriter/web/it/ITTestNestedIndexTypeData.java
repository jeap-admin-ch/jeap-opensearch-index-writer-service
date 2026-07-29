package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Data class in the shape the index type registry generates for {@code it-test-nested-index-mapping.json}:
 * an {@code object} field becomes a single record, a {@code nested} field becomes a {@code List} of
 * records, because an OpenSearch {@code nested} field holds an array of objects.
 */
public record ITTestNestedIndexTypeData(
        @JsonProperty("single_object") SingleObject singleObject,
        List<Cases> cases
) {

    public record SingleObject(String name) {}

    public record Cases(
            @JsonProperty("case_reference") String caseReference,
            @JsonProperty("control_pattern") ControlPattern controlPattern
    ) {

        public record ControlPattern(@JsonProperty("factual_name") String factualName) {}
    }
}
