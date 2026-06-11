package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ITReferenceProvider implements ReferenceProvider<Message> {

    @Override
    public List<OriginReference> extractReference(Message message) {
        return List.of(new OriginReference("TestDocument", "doc-1", null));
    }
}
