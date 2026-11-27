package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
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

class UpsertSearchItemCommandListenerTest {

    private final TaskCommandProcessor processor = mock(TaskCommandProcessor.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final UpsertSearchItemCommandListener listener = new UpsertSearchItemCommandListener(processor);
    private final Job job = MaintenanceTestJobs.job(JobType.UPSERT);
    private final JobTask task = MaintenanceTestJobs.task(job, 0, "12345", "17");

    @Test
    void onMessage_processesTheTaskAndThenAcknowledges() {
        listener.onMessage(record(command()), acknowledgment);

        var order = inOrder(processor, acknowledgment);
        order.verify(processor).process(new TaskCommand(JobType.UPSERT, job.jobId(), task.taskId(), "12345", "17",
                "JmeDecreeDocumentCreatedEvent", "jme-decree-document-created"));
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void onMessage_acknowledgesACommandWithAnInvalidIdWithoutProcessingIt() {
        UpsertSearchItemCommand command = command();
        command.getPayload().setTaskId("not-a-uuid");

        listener.onMessage(record(command), acknowledgment);

        verifyNoInteractions(processor);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void onMessage_doesNotAcknowledgeWhenTheResultCouldNotBeSaved() {
        doThrow(new JobStorageUnavailableException("task index down", null)).when(processor).process(any());

        assertThatThrownBy(() -> listener.onMessage(record(command()), acknowledgment))
                .isInstanceOf(JobStorageUnavailableException.class);

        verifyNoInteractions(acknowledgment);
    }

    private UpsertSearchItemCommand command() {
        return UpsertSearchItemCommandBuilder.builder("JEAP", "my-index-writer").task(job, task).build();
    }

    private static ConsumerRecord<AvroMessageKey, UpsertSearchItemCommand> record(UpsertSearchItemCommand command) {
        return new ConsumerRecord<>("my-upsert-command", 0, 0L, null, command);
    }
}
