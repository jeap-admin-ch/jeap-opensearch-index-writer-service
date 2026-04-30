package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ITTestIndexTypeData(
        @JsonProperty("camel_case_field") String camelCaseField,
        @JsonProperty("another_camel_field") String anotherCamelField
) {
}
