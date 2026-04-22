package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import java.util.List;

record MessageConfigRaw(
        String messageName,
        String topicName,
        List<MessageOperationConfigRaw> operations
) {
}
