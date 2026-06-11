package ch.admin.bit.jeap.opensearch.client.domain;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * Links search item data to its corresponding index type.
 * This is necessary because there is no direct navigation from the generated data type
 * to the matching {@link IndexType}.
 *
 * @param origin the origin metadata of the search item
 * @param data the typed search item payload
 * @param indexType the index type describing the OpenSearch index for the payload type
 * @param <T> the type of the search item payload
 */
public record SearchItemTyped<T>(Origin origin, T data, @JsonIgnore IndexType<T> indexType) implements SearchItemView {
    public SearchItemTyped {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(indexType, "indexType must not be null");
    }
}
