package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import org.springframework.stereotype.Component;

@Component
class ITReferenceProvider implements ReferenceProvider<Message> {

    @Override
    public OriginReference extractReference(Message message) {
        return new OriginReference("TestDocument", "doc-1", null);
    }
}
