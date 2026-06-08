package ch.admin.bit.jeap.opensearch.indextype;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchItemMetadataTest {

    @Test
    void initialSetsUpsertedAtToNow() {
        Instant before = Instant.now();
        SearchItemMetadata meta = SearchItemMetadata.initial(1, 2);
        Instant after = Instant.now();

        assertThat(meta.upsertedAt()).isBetween(before, after);
    }

    @Test
    void initialSetsMajorVersion() {
        SearchItemMetadata meta = SearchItemMetadata.initial(4, 0);
        assertThat(meta.majorVersion()).isEqualTo(4);
    }

    @Test
    void initialSetsMinorVersion() {
        SearchItemMetadata meta = SearchItemMetadata.initial(1, 3);
        assertThat(meta.minorVersion()).isEqualTo(3);
    }
}
