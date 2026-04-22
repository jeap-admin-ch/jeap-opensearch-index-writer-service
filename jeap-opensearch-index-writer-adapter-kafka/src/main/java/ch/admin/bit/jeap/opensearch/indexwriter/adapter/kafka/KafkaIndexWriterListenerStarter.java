package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
class KafkaIndexWriterListenerStarter {

    private final MessageConfigurationRepository messageConfigurationRepository;
    private final KafkaIndexWriterConsumerFactory consumerFactory;

    @EventListener(ApplicationReadyEvent.class) // We want to start after all beans have been created, to make sure all things that need to be done are done E.g. Mapping updates
    void start() {
        List<MessageConfig> configs = messageConfigurationRepository.getAll();
        if (configs.isEmpty()) {
            log.info("No message configurations found, no Kafka consumers will be started");
            return;
        }

        Map<String, Set<String>> messageNamesByTopic = configs.stream()
                .collect(Collectors.groupingBy(
                        MessageConfig::topicName,
                        Collectors.mapping(MessageConfig::messageName, Collectors.toSet())));

        messageNamesByTopic.forEach(consumerFactory::startConsumer);
    }
}
