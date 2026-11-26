package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.opensearch.command.upsert.SearchItemReference;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommandPayload;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskCommandProcessor;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

// Acknowledges only after the task result is saved; failures to save are left to the container's error handler
@RequiredArgsConstructor
@Slf4j
class UpsertSearchItemCommandListener implements AcknowledgingMessageListener<AvroMessageKey, UpsertSearchItemCommand> {

    private final TaskCommandProcessor processor;

    @Override
    public void onMessage(ConsumerRecord<AvroMessageKey, UpsertSearchItemCommand> consumerRecord, Acknowledgment acknowledgment) {
        TaskCommand command = toTaskCommand(consumerRecord.value());
        if (command != null) {
            processor.process(command);
        }
        acknowledgment.acknowledge();
    }

    private static TaskCommand toTaskCommand(UpsertSearchItemCommand message) {
        UpsertSearchItemCommandPayload payload = message.getPayload();
        SearchItemReference reference = message.getReferences().getSearchItem();
        try {
            return new TaskCommand(JobType.UPSERT, UUID.fromString(payload.getJobId()), UUID.fromString(payload.getTaskId()),
                    reference.getReferenceId(), reference.getReferenceVersion(), payload.getMessageName(), payload.getTopicName());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring UpsertSearchItemCommand {} with invalid job id '{}' or task id '{}'",
                    message.getIdentity().getId(), payload.getJobId(), payload.getTaskId());
            return null;
        }
    }
}
