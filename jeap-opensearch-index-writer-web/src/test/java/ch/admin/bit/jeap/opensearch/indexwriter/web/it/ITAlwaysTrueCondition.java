package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import org.springframework.stereotype.Component;

@Component
class ITAlwaysTrueCondition implements IndexingCondition<Message> {

    @Override
    public boolean evaluate(Message message) {
        return true;
    }
}
