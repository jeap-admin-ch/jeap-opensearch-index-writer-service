package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MessageIndexingReceiver {

    private final MessageConfigurationRepository configurationRepository;
    private final MessageIndexingService indexingService;

    public void messageReceived(Message message) {
        String typeName = message.getType().getName();
        MessageConfig config = configurationRepository.findByName(typeName)
                .orElseThrow(() -> IndexingException.noIndexWriterConfigFound(typeName));

        log.debug("Processing {} operation(s) for message '{}'", config.operations().size(), typeName);
        config.operations().forEach(operation -> indexingService.index(message, operation));
    }
}
