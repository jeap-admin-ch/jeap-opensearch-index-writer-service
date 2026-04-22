package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.domainevent.avro.AvroDomainEventBuilder;
import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsProvider;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsValidator;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import ch.admin.bit.jme.declaration.DeclarationPayload;
import ch.admin.bit.jme.declaration.DeclarationReferences;
import ch.admin.bit.jme.declaration.JmeDeclarationCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class KafkaIndexWriterConsumerFactoryIT extends KafkaIntegrationTestBase {

    private static final String TEST_TOPIC = "test-consumer-factory";

    @MockitoBean
    private ContractsValidator contractsValidator;

    @MockitoBean
    private ContractsProvider contractsProvider;

    @MockitoBean
    private MessageIndexingReceiver messageIndexingReceiver;

    @Autowired
    private KafkaIndexWriterConsumerFactory consumerFactory;

    @BeforeEach
    void waitForContainerAssignment() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !consumerFactory.getContainers().isEmpty());
        consumerFactory.getContainers().forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));
    }

    @Test
    void receivedMessageIsForwardedToMessageIndexingReceiver() {
        sendSync(TEST_TOPIC, declarationEvent("consumer-factory-it-1"));

        verify(messageIndexingReceiver, timeout(TEST_TIMEOUT)).messageReceived(any());
    }

    private static JmeDeclarationCreatedEvent declarationEvent(String idempotenceId) {
        return new TestEventBuilder()
                .idempotenceId(idempotenceId)
                .build();
    }

    private static class TestEventBuilder extends AvroDomainEventBuilder<TestEventBuilder, JmeDeclarationCreatedEvent> {

        TestEventBuilder() {
            super(JmeDeclarationCreatedEvent::new);
        }

        @Override
        protected String getServiceName() {
            return "jeap-opensearch-index-writer-kafka-it";
        }

        @Override
        protected String getSystemName() {
            return "JME";
        }

        @Override
        protected TestEventBuilder self() {
            return this;
        }

        @Override
        public JmeDeclarationCreatedEvent build() {
            setReferences(DeclarationReferences.newBuilder().build());
            setPayload(DeclarationPayload.newBuilder().setMessage("test").build());
            return super.build();
        }
    }
}
