package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message;

import java.util.List;

public record MessageConfig(
        String messageName,
        String topicName,
        List<MessageOperationConfig> operations
) {
}
