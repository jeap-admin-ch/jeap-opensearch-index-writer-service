package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.messaging.avro.AvroMessage;
import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.messaging.contract.v2.Contract;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsProvider;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsValidator;
import ch.admin.bit.jeap.messaging.kafka.properties.KafkaProperties;
import ch.admin.bit.jeap.messaging.kafka.spring.JeapKafkaBeanNames;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class KafkaIndexWriterConsumerFactory {

    @Getter
    private final List<ConcurrentMessageListenerContainer<?, ?>> containers = new CopyOnWriteArrayList<>();
    private final MessageIndexingReceiver messageIndexingReceiver;
    private final ContractsValidator contractsValidator;
    private final ContractsProvider contractsProvider;
    private final KafkaProperties kafkaProperties;
    private final BeanFactory beanFactory;
    private final JeapKafkaBeanNames jeapKafkaBeanNames;

    KafkaIndexWriterConsumerFactory(MessageIndexingReceiver messageIndexingReceiver,
                                    ContractsValidator contractsValidator,
                                    ContractsProvider contractsProvider,
                                    KafkaProperties kafkaProperties,
                                    BeanFactory beanFactory) {
        this.messageIndexingReceiver = messageIndexingReceiver;
        this.contractsValidator = contractsValidator;
        this.contractsProvider = contractsProvider;
        this.kafkaProperties = kafkaProperties;
        this.beanFactory = beanFactory;
        this.jeapKafkaBeanNames = new JeapKafkaBeanNames(kafkaProperties.getDefaultClusterName());
    }

    void startConsumer(String topicName, Set<String> messageNames) {
        String clusterName = kafkaProperties.getDefaultClusterName();
        log.info("Starting index writer listener for message(s) '{}' on topic '{}' on cluster '{}'",
                messageNames, topicName, clusterName);

        messageNames.forEach(messageName -> ensureConsumerContract(topicName, messageName));

        KafkaIndexWriterMessageListener listener = new KafkaIndexWriterMessageListener(messageNames, messageIndexingReceiver);

        ConcurrentMessageListenerContainer<AvroMessageKey, AvroMessage> container = getListenerContainerFactory(clusterName).createContainer(topicName);
        container.setupMessageListener(listener);
        container.start();
        containers.add(container);
    }

    private void ensureConsumerContract(String topicName, String messageName) {
        List<String> matchingVersions = contractsProvider.getContracts().stream()
                .filter(contract -> contract.getMessageTypeName().equals(messageName)
                        && contract.getRole().equalsIgnoreCase("consumer"))
                .map(Contract::getMessageTypeVersion)
                .toList();

        if (matchingVersions.isEmpty()) {
            contractsValidator.ensureConsumerContract(messageName, topicName);
        } else {
            matchingVersions.forEach(version -> ensureConsumerContract(topicName, messageName, version));
        }
    }

    private void ensureConsumerContract(String topicName, String messageName, String version) {
        AvroMessageType type = new AvroMessageType();
        type.setName(messageName);
        type.setVersion(version);
        contractsValidator.ensureConsumerContract(type, topicName);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentKafkaListenerContainerFactory<AvroMessageKey, AvroMessage> getListenerContainerFactory(String clusterName) {
        try {
            return (ConcurrentKafkaListenerContainerFactory<AvroMessageKey, AvroMessage>)
                    beanFactory.getBean(jeapKafkaBeanNames.getListenerContainerFactoryBeanName(clusterName));
        } catch (NoSuchBeanDefinitionException ex) {
            log.error("No kafkaListenerContainerFactory found for cluster '{}'", clusterName);
            throw new IllegalStateException("No kafkaListenerContainerFactory found for cluster " + clusterName);
        }
    }

    @PreDestroy
    void stop() {
        log.info("Stopping all index writer Kafka listener containers...");
        containers.forEach(c -> c.stop(true));
    }
}
