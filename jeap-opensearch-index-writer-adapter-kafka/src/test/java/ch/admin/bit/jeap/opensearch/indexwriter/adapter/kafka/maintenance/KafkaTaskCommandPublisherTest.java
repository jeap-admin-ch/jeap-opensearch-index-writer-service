package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessage;
import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.kafka.properties.KafkaProperties;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskDispatchException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KafkaTaskCommandPublisherTest {

    private static final String TOPIC = "my-upsert-command";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<AvroMessageKey, AvroMessage> kafkaTemplate = mock(KafkaTemplate.class);
    private KafkaTaskCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        BeanFactory beanFactory = mock(BeanFactory.class);
        when(beanFactory.getBean(anyString())).thenReturn(kafkaTemplate);
        KafkaProperties kafkaProperties = mock(KafkaProperties.class);
        when(kafkaProperties.getDefaultClusterName()).thenReturn("default");
        when(kafkaProperties.getSystemName()).thenReturn("JEAP");
        when(kafkaProperties.getServiceName()).thenReturn("my-index-writer");
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setUpsertCommandTopic(TOPIC);
        publisher = new KafkaTaskCommandPublisher(beanFactory, properties, kafkaProperties);
    }

    @Test
    void publish_sendsOneCommandPerTaskWithoutKeyToTheConfiguredTopic() {
        Job job = MaintenanceTestJobs.job(JobType.UPSERT);
        List<JobTask> tasks = List.of(MaintenanceTestJobs.task(job, 0, "a", null), MaintenanceTestJobs.task(job, 1, "b", "2"));
        when(kafkaTemplate.send(eq(TOPIC), any(AvroMessage.class))).thenReturn(CompletableFuture.completedFuture(sendResult()));

        publisher.publish(job, tasks);

        ArgumentCaptor<AvroMessage> sent = ArgumentCaptor.forClass(AvroMessage.class);
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), sent.capture());
        assertThat(sent.getAllValues()).extracting(m -> ((UpsertSearchItemCommand) m).getPayload().getTaskId())
                .containsExactly(tasks.get(0).taskId().toString(), tasks.get(1).taskId().toString());
    }

    @Test
    void publish_reportsAFailedSendAsDispatchFailure() {
        Job job = MaintenanceTestJobs.job(JobType.UPSERT);
        when(kafkaTemplate.send(eq(TOPIC), any(AvroMessage.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> publisher.publish(job, List.of(MaintenanceTestJobs.task(job, 0, "a", null))))
                .isInstanceOf(TaskDispatchException.class)
                .hasRootCauseMessage("broker down");
    }

    @Test
    void publish_refusesDeleteJobs() {
        Job job = MaintenanceTestJobs.job(JobType.DELETE);

        assertThatThrownBy(() -> publisher.publish(job, List.of(MaintenanceTestJobs.task(job, 0, "a", null))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(kafkaTemplate);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<AvroMessageKey, AvroMessage> sendResult() {
        return mock(SendResult.class);
    }
}
