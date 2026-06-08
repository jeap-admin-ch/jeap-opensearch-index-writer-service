package ch.admin.bit.jeap.opensearch.indextype;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchItemTest {

    record Data(String value) {}

    @Test
    void withMetadataProducesSearchItemIndexed() {
        Origin origin = origin("id-1");
        Data data = new Data("hello");
        SearchItem<Data> item = new SearchItem<>(origin, data);
        SearchItemMetadata meta = SearchItemMetadata.initial(1, 1);

        SearchItemIndexed<Data> enriched = item.withMetadata(meta);

        assertThat(enriched.searchItem()).isEqualTo(meta);
        assertThat(enriched.origin()).isEqualTo(origin);
        assertThat(enriched.data()).isEqualTo(data);
    }

    @Test
    void enrichedSearchItemOfFactoryEquivalentToWithMetadata() {
        Origin origin = origin("id-2");
        Data data = new Data("world");
        SearchItem<Data> item = new SearchItem<>(origin, data);
        SearchItemMetadata meta = SearchItemMetadata.initial(1, 1);

        SearchItemIndexed<Data> viaWith = item.withMetadata(meta);
        SearchItemIndexed<Data> viaOf = SearchItemIndexed.of(item, meta);

        assertThat(viaWith).isEqualTo(viaOf);
    }

    @Test
    void toBaseRoundTrips() {
        Origin origin = origin("id-3");
        Data data = new Data("round-trip");
        SearchItem<Data> original = new SearchItem<>(origin, data);

        SearchItem<Data> roundTripped = original.withMetadata(SearchItemMetadata.initial(1, 0)).toBase();

        assertThat(roundTripped).isEqualTo(original);
    }

    private static Origin origin(String id) {
        return new Origin(id, "v1", "bp-1", null, Instant.EPOCH, Instant.EPOCH, null);
    }
}
