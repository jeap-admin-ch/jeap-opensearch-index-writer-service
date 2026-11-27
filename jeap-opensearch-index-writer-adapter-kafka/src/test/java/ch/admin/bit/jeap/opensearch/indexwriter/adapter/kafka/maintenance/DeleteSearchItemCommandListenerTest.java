package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommandProcessor;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeleteSearchItemCommandListenerTest {

    private final TaskCommandProcessor processor = mock(TaskCommandProcessor.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final DeleteSearchItemCommandListener listener = new DeleteSearchItemCommandListener(processor);
    private final Job job = MaintenanceTestJobs.job(JobType.DELETE);
    private final JobTask task = MaintenanceTestJobs.task(job, 0, "12345", null);

    @Test
    void onMessage_processesTheTaskAndThenAcknowledges() {
        listener.onMessage(consumerRecord(command()), acknowledgment);

        var order = inOrder(processor, acknowledgment);
        order.verify(processor).process(new TaskCommand(JobType.DELETE, job.jobId(), task.taskId(), "12345", null, null, null));
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void onMessage_acknowledgesACommandWithAnInvalidIdWithoutProcessingIt() {
        DeleteSearchItemCommand command = command();
        command.getPayload().setJobId("not-a-uuid");

        listener.onMessage(consumerRecord(command), acknowledgment);

        verifyNoInteractions(processor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void onMessage_doesNotAcknowledgeWhenTheResultCouldNotBeSaved() {
        doThrow(new JobStorageUnavailableException("task index down", null)).when(processor).process(any());
        ConsumerRecord<AvroMessageKey, DeleteSearchItemCommand> message = consumerRecord(command());

        assertThatThrownBy(() -> listener.onMessage(message, acknowledgment))
                .isInstanceOf(JobStorageUnavailableException.class);

        verifyNoInteractions(acknowledgment);
    }

    private DeleteSearchItemCommand command() {
        return DeleteSearchItemCommandBuilder.builder("JEAP", "my-index-writer").task(job, task).build();
    }

    private static ConsumerRecord<AvroMessageKey, DeleteSearchItemCommand> consumerRecord(DeleteSearchItemCommand command) {
        return new ConsumerRecord<>("my-delete-command", 0, 0L, null, command);
    }
}
