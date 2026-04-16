package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Origin is the business object
 * @param id id of the business object
 * @param version version of the business object
 * @param bpId business partner id
 * @param created creation timestamp of the business object
 * @param modified modified timestamp of the business object
 * @param reference deep link to the business object
 */
public record Origin(String id,
                     String version,
                     @JsonProperty("bp_id") String bpId,
                     Instant created,
                     Instant modified,
                     JsonNode reference
) {
}