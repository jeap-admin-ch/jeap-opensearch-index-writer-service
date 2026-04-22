package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference;

public record OriginReference(
        String originType,   // mandatory
        String id,           // mandatory
        String version       // optional, nullable
) {
}
