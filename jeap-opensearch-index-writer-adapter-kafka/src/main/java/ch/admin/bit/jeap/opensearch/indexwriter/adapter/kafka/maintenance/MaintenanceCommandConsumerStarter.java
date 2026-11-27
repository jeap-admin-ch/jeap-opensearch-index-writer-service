package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsValidator;
import ch.admin.bit.jeap.messaging.kafka.properties.KafkaProperties;
import ch.admin.bit.jeap.messaging.kafka.spring.JeapKafkaBeanNames;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommandProcessor;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageRejectedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMaintenanceEnabled
@Slf4j
class MaintenanceCommandConsumerStarter {

    private final TaskCommandProcessor processor;
    private final MaintenanceProperties properties;
    private final KafkaProperties kafkaProperties;
    private final ContractsValidator contractsValidator;
    private final BeanFactory beanFactory;
    private final List<ConcurrentMessageListenerContainer<AvroMessageKey, ?>> containers = new ArrayList<>();

    MaintenanceCommandConsumerStarter(TaskCommandProcessor processor, MaintenanceProperties properties, KafkaProperties kafkaProperties,
                                      ContractsValidator contractsValidator, BeanFactory beanFactory) {
        this.processor = processor;
        this.properties = properties;
        this.kafkaProperties = kafkaProperties;
        this.contractsValidator = contractsValidator;
        this.beanFactory = beanFactory;
    }

    // Fails the startup if the service instance did not declare the contracts for its command topic
    @PostConstruct
    void verifyContracts() {
        verifyContracts(UpsertSearchItemCommandBuilder.messageType(), properties.getUpsertCommandTopic());
        verifyContracts(DeleteSearchItemCommandBuilder.messageType(), properties.getDeleteCommandTopic());
    }

    private void verifyContracts(AvroMessageType type, String topic) {
        contractsValidator.ensurePublisherContract(type, topic);
        contractsValidator.ensureConsumerContract(type, topic);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        start(properties.getUpsertCommandTopic(), new UpsertSearchItemCommandListener(processor));
        start(properties.getDeleteCommandTopic(), new DeleteSearchItemCommandListener(processor));
    }

    private void start(String topic, Object listener) {
        log.info("Starting maintenance command listener on topic '{}' with concurrency {}", topic, properties.getCommandConsumerConcurrency());
        ConcurrentMessageListenerContainer<AvroMessageKey, ?> container = listenerContainerFactory().createContainer(topic);
        container.setConcurrency(properties.getCommandConsumerConcurrency());
        // Commands are work items: those sent before the consumer group first joined the topic must not be skipped.
        // Reading them again is harmless, finished tasks and unknown jobs are not run.
        container.getContainerProperties().getKafkaConsumerProperties().setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        container.setCommonErrorHandler(errorHandler());
        container.setupMessageListener(listener);
        container.start();
        containers.add(container);
    }

    // Task failures end up in the job report, never on the jEAP error topic. Storage failures, including rejected reads
    // and updates, require redelivery with back-off until the result can be saved. Anything else
    // (e.g. an unreadable command) is logged and skipped, the housekeeping sends the command of an unfinished task again.
    private static DefaultErrorHandler errorHandler() {
        return errorHandler((consumerRecord, exception) -> log.error("Skipping maintenance command at offset {} of {}-{}",
                consumerRecord.offset(), consumerRecord.topic(), consumerRecord.partition(), exception));
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer skipped) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2);
        backOff.setMaxInterval(60_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(skipped, backOff);
        handler.setClassifications(Map.of(JobStorageUnavailableException.class, true, JobStorageRejectedException.class, true), false);
        return handler;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentKafkaListenerContainerFactory<AvroMessageKey, ?> listenerContainerFactory() {
        String cluster = kafkaProperties.getDefaultClusterName();
        return (ConcurrentKafkaListenerContainerFactory<AvroMessageKey, ?>)
                beanFactory.getBean(new JeapKafkaBeanNames(cluster).getListenerContainerFactoryBeanName(cluster));
    }

    @PreDestroy
    void stop() {
        containers.forEach(container -> container.stop(true));
    }
}
