package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference;

import ch.admin.bit.jeap.messaging.model.Message;

public interface ReferenceProvider<M extends Message> {

    OriginReference extractReference(M message);
}
