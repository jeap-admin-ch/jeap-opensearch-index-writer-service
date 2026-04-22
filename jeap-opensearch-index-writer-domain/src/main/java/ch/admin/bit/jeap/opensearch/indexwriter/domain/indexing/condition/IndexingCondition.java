package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition;

import ch.admin.bit.jeap.messaging.model.Message;

public interface IndexingCondition<M extends Message> {

    boolean evaluate(M message);
}
