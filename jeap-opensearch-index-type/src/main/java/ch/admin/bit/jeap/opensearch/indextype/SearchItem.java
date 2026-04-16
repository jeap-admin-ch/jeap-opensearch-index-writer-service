package ch.admin.bit.jeap.opensearch.indextype;

public record SearchItem<T>(Origin origin, T data) {

    public SearchItemIndexed<T> withMetadata(SearchItemMetadata meta) {
        return new SearchItemIndexed<>(meta, origin, data);
    }
}