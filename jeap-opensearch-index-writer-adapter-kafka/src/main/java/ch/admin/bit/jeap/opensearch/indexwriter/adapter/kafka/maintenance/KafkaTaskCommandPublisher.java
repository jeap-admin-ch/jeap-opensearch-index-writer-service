package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessage;
import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.kafka.properties.KafkaProperties;
import ch.admin.bit.jeap.messaging.kafka.spring.JeapKafkaBeanNames;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommandPublisher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskDispatchException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnMaintenanceEnabled
class KafkaTaskCommandPublisher implements TaskCommandPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofMinutes(1);

    private final KafkaTemplate<AvroMessageKey, AvroMessage> kafkaTemplate;
    private final MaintenanceProperties properties;
    private final KafkaProperties kafkaProperties;

    @SuppressWarnings("unchecked")
    KafkaTaskCommandPublisher(BeanFactory beanFactory, MaintenanceProperties properties, KafkaProperties kafkaProperties) {
        String cluster = kafkaProperties.getDefaultClusterName();
        this.kafkaTemplate = (KafkaTemplate<AvroMessageKey, AvroMessage>)
                beanFactory.getBean(new JeapKafkaBeanNames(cluster).getKafkaTemplateBeanName(cluster));
        this.properties = properties;
        this.kafkaProperties = kafkaProperties;
    }

    private AvroMessage command(Job job, JobTask task) {
        String system = kafkaProperties.getSystemName();
        String service = kafkaProperties.getServiceName();
        return switch (job.type()) {
            case UPSERT -> UpsertSearchItemCommandBuilder.builder(system, service).task(job, task).build();
            case DELETE -> DeleteSearchItemCommandBuilder.builder(system, service).task(job, task).build();
        };
    }

    // No key: the commands spread over all partitions, their order does not matter
    @Override
    public void publish(Job job, List<JobTask> tasks) {
        String topic = job.type() == JobType.UPSERT ? properties.getUpsertCommandTopic() : properties.getDeleteCommandTopic();
        CompletableFuture<?>[] sends = tasks.stream()
                .map(task -> kafkaTemplate.send(topic, command(job, task)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(sends).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskDispatchException("Interrupted while sending the commands of job " + job.jobId(), e);
        } catch (ExecutionException e) {
            throw new TaskDispatchException("Sending the commands of job %s to topic %s failed".formatted(job.jobId(), topic), e.getCause());
        } catch (TimeoutException e) {
            throw new TaskDispatchException("Kafka did not confirm the commands of job %s within %s".formatted(job.jobId(), SEND_TIMEOUT), e);
        }
    }
}
