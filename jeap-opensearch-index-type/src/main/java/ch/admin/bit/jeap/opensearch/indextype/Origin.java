package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Origin is the business object
 *
 * @param id        id of the business object
 * @param version   version of the business object
 * @param bpId      business partner id
 * @param tenant    tenant of the business object
 * @param created   creation timestamp of the business object
 * @param modified  modified timestamp of the business object
 * @param reference reference to the business object (e.g. url or other identifier)
 *                  that can used to retrieve the original business object
 */
public record Origin(String id,
                     String version,
                     @JsonProperty("bp_id") String bpId,
                     String tenant,
                     Instant created,
                     Instant modified,
                     Map<String, String> reference
) {
}
