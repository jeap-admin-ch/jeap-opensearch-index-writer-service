package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SearchItemMetadata(
        @JsonProperty("upserted_at") Instant upsertedAt,
        @JsonProperty("minor_version") int minorVersion
) {
    public static SearchItemMetadata initial(int minorVersion) {
        return new SearchItemMetadata(Instant.now(), minorVersion);
    }
}
