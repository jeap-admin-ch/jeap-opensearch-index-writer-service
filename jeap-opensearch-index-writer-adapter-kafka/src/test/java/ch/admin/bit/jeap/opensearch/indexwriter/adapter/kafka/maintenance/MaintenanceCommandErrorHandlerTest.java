package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageRejectedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MaintenanceCommandErrorHandlerTest {

    private final List<ConsumerRecord<?, ?>> skipped = new ArrayList<>();
    private final DefaultErrorHandler handler = MaintenanceCommandConsumerStarter.errorHandler((r, e) -> skipped.add(r));
    private final ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("my-upsert-command", 0, 7L, null, "command");

    @Test
    void storageFailuresAreDeliveredAgainInsteadOfSkipped() {
        assertThat(handle(new JobStorageUnavailableException("task index down", null))).as("not recovered").isFalse();
        assertThat(handle(new JobStorageRejectedException("write block"))).as("not recovered").isFalse();
        assertThat(skipped).isEmpty();
    }

    @Test
    void otherFailuresAreSkipped() {
        assertThat(handle(new IllegalStateException("unreadable command"))).as("recovered").isTrue();
        assertThat(skipped).containsExactly(consumerRecord);
    }

    // The container wraps the listener's exception, the classification must look at its cause
    private boolean handle(RuntimeException cause) {
        return handler.handleOne(new ListenerExecutionFailedException("listener failed", cause), consumerRecord,
                mock(Consumer.class), mock(MessageListenerContainer.class));
    }
}
