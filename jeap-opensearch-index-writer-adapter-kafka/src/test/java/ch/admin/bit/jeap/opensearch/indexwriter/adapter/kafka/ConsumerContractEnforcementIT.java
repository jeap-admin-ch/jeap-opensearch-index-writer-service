package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.messaging.kafka.contract.NoContractException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerContractEnforcementIT {

    @Test
    void applicationContextFailsToStartWithoutConsumerContractAnnotation() {
        assertThatThrownBy(() ->
                new SpringApplicationBuilder(KafkaIndexWriterConsumerFactoryITConfig.class, TestConfig.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                "spring.application.name=consumer-contract-enforcement-it",
                                "jeap.messaging.kafka.embedded=true")
                        .run()
        )
        .isInstanceOf(NoContractException.class)
        .hasMessageContaining("JmeDeclarationCreatedEvent");
    }

    @Configuration
    static class TestConfig {
        @Bean
        MessageIndexingReceiver messageIndexingReceiver() {
            return Mockito.mock(MessageIndexingReceiver.class);
        }
    }
}
