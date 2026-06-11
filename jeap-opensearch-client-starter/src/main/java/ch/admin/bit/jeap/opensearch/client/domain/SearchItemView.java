package ch.admin.bit.jeap.opensearch.client.domain;

import ch.admin.bit.jeap.opensearch.indextype.IndexTypeDescriptor;
import ch.admin.bit.jeap.opensearch.indextype.Origin;

/**
 * Non-generic view of a deserialized search item, suitable for use in heterogeneous
 * collections that mix multiple index-type versions (each with a different data type).
 * Callers that need the concrete data type should cast {@link #data()} after checking
 * {@link #indexType()} to determine the expected type.
 *
 * @see SearchItemTyped
 */
public interface SearchItemView {

    Origin origin();

    Object data();

    IndexTypeDescriptor indexType();
}
