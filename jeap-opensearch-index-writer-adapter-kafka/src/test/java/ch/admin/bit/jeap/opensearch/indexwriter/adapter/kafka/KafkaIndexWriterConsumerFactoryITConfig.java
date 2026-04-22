package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.DomainConfiguration;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;

@SpringBootApplication(exclude = DomainConfiguration.class)
class KafkaIndexWriterConsumerFactoryITConfig {

    static void main(String[] args) {
        SpringApplication.run(KafkaIndexWriterConsumerFactoryITConfig.class, args);
    }

    @Bean
    MessageConfigurationRepository testMessageConfigurationRepository() {
        List<MessageConfig> configs = List.of(
                new MessageConfig("JmeDeclarationCreatedEvent", "test-consumer-factory", List.of()));
        return new MessageConfigurationRepository() {
            @Override
            public List<MessageConfig> getAll() {
                return configs;
            }

            @Override
            public Optional<MessageConfig> findByName(String messageName) {
                return configs.stream().filter(c -> c.messageName().equals(messageName)).findFirst();
            }
        };
    }
}
