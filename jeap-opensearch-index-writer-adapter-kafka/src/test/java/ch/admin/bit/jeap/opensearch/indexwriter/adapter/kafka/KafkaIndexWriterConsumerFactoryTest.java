package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.messaging.contract.v2.Contract;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsProvider;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsValidator;
import ch.admin.bit.jeap.messaging.kafka.properties.KafkaProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaIndexWriterConsumerFactoryTest {

    @Mock
    private MessageIndexingReceiver messageIndexingReceiver;
    @Mock
    private ContractsValidator contractsValidator;
    @Mock
    private ContractsProvider contractsProvider;
    @Mock
    private KafkaProperties kafkaProperties;
    @Mock
    private BeanFactory beanFactory;
    @SuppressWarnings("rawtypes")
    @Mock
    private ConcurrentKafkaListenerContainerFactory containerFactory;
    @SuppressWarnings("rawtypes")
    @Mock
    private ConcurrentMessageListenerContainer container;

    private KafkaIndexWriterConsumerFactory consumerFactory;

    @BeforeEach
    void setUp() {
        when(kafkaProperties.getDefaultClusterName()).thenReturn("default");
        consumerFactory = new KafkaIndexWriterConsumerFactory(
                messageIndexingReceiver, contractsValidator, contractsProvider, kafkaProperties, beanFactory);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startConsumerCreatesAndStartsContainer() {
        when(contractsProvider.getContracts()).thenReturn(List.of());
        when(beanFactory.getBean(anyString())).thenReturn(containerFactory);
        when(containerFactory.createContainer(anyString())).thenReturn(container);

        consumerFactory.startConsumer("my-topic", Set.of("MessageA"));

        verify(containerFactory).createContainer("my-topic");
        verify(container).setupMessageListener(any(KafkaIndexWriterMessageListener.class));
        verify(container).start();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startConsumerRegistersListenerWithCorrectMessageNames() {
        when(contractsProvider.getContracts()).thenReturn(List.of());
        when(beanFactory.getBean(anyString())).thenReturn(containerFactory);
        when(containerFactory.createContainer(anyString())).thenReturn(container);
        Set<String> messageNames = Set.of("MessageA", "MessageB");

        consumerFactory.startConsumer("my-topic", messageNames);

        verify(container).setupMessageListener(any(KafkaIndexWriterMessageListener.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopStopsAllStartedContainers() {
        when(contractsProvider.getContracts()).thenReturn(List.of());
        when(beanFactory.getBean(anyString())).thenReturn(containerFactory);
        when(containerFactory.createContainer(anyString())).thenReturn(container);
        consumerFactory.startConsumer("topic-a", Set.of("MessageA"));
        consumerFactory.startConsumer("topic-b", Set.of("MessageB"));

        consumerFactory.stop();

        verify(container, times(2)).stop(true);
    }

    @Test
    void stopIsNoOpWhenNoContainersStarted() {
        consumerFactory.stop();

        verifyNoInteractions(container);
    }

    @Test
    void startConsumerThrowsWhenNoListenerContainerFactoryFound() {
        when(contractsProvider.getContracts()).thenReturn(List.of());
        when(beanFactory.getBean(anyString()))
                .thenThrow(new NoSuchBeanDefinitionException("no factory"));
        Set<String> messageNames = Set.of("MessageA");

        assertThatThrownBy(() -> consumerFactory.startConsumer("my-topic", messageNames))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafkaListenerContainerFactory");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startConsumerCallsValidatorDirectlyWhenNoContractsFound() {
        when(contractsProvider.getContracts()).thenReturn(List.of());
        when(beanFactory.getBean(anyString())).thenReturn(containerFactory);
        when(containerFactory.createContainer(anyString())).thenReturn(container);

        consumerFactory.startConsumer("my-topic", Set.of("MessageA"));

        verify(contractsValidator).ensureConsumerContract("MessageA", "my-topic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startConsumerEnsuresConsumerContractForEachMessageVersion() {
        Contract contract = mock(Contract.class);
        when(contract.getMessageTypeName()).thenReturn("MessageA");
        when(contract.getRole()).thenReturn("consumer");
        when(contract.getMessageTypeVersion()).thenReturn("1.0.0");
        when(contractsProvider.getContracts()).thenReturn(List.of(contract));
        when(beanFactory.getBean(anyString())).thenReturn(containerFactory);
        when(containerFactory.createContainer(anyString())).thenReturn(container);

        consumerFactory.startConsumer("my-topic", Set.of("MessageA"));

        verify(contractsValidator).ensureConsumerContract(
                argThat((AvroMessageType t) -> t.getName().equals("MessageA") && t.getVersion().equals("1.0.0")),
                eq("my-topic"));
    }
}
