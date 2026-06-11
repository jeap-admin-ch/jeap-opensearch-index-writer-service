package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference;

import ch.admin.bit.jeap.messaging.model.Message;

import java.util.List;

public interface ReferenceProvider<M extends Message> {

    List<OriginReference> extractReference(M message);
}
