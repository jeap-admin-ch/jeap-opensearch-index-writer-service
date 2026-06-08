package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SearchItemMetadata(
        @JsonProperty("upserted_at") Instant upsertedAt,
        @JsonProperty("major_version") int majorVersion,
        @JsonProperty("minor_version") int minorVersion
) {
    public static SearchItemMetadata initial(int majorVersion, int minorVersion) {
        return new SearchItemMetadata(Instant.now(), majorVersion, minorVersion);
    }
}
