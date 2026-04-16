package ch.admin.bit.jeap.opensearch.indextype;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchItemMetadataTest {

    @Test
    void initialSetsUpsertedAtToNow() {
        Instant before = Instant.now();
        SearchItemMetadata meta = SearchItemMetadata.initial(2);
        Instant after = Instant.now();

        assertThat(meta.upsertedAt()).isBetween(before, after);
    }

    @Test
    void initialSetsMinorVersion() {
        SearchItemMetadata meta = SearchItemMetadata.initial(3);
        assertThat(meta.minorVersion()).isEqualTo(3);
    }
}
