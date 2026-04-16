package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchItemIndexed<T>(
        @JsonProperty("search_item") SearchItemMetadata searchItem,
        Origin origin,
        T data
) {

    public static <T> SearchItemIndexed<T> of(SearchItem<T> base, SearchItemMetadata meta) {
        return new SearchItemIndexed<>(meta, base.origin(), base.data());
    }

    public SearchItem<T> toBase() {
        return new SearchItem<>(origin, data);
    }
}