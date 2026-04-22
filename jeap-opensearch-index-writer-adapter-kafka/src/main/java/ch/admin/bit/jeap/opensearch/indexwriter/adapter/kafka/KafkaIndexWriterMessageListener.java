package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.messaging.avro.AvroMessage;
import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.avro.errorevent.MessageHandlerException;
import ch.admin.bit.jeap.messaging.avro.errorevent.MessageHandlerExceptionInformation.Temporality;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Set;

@RequiredArgsConstructor
@Slf4j
class KafkaIndexWriterMessageListener implements AcknowledgingMessageListener<AvroMessageKey, AvroMessage> {

    private static final String ERROR_CODE = "index-writer-failed";

    private final Set<String> messageTypeNames;
    private final MessageIndexingReceiver messageIndexingReceiver;

    @Override
    public void onMessage(ConsumerRecord<AvroMessageKey, AvroMessage> consumerRecord, Acknowledgment acknowledgment) {
        String typeName = consumerRecord.value().getType().getName();
        if (!messageTypeNames.contains(typeName)) {
            log.debug("Ignoring message {} on topic {} — no matching configuration", typeName, consumerRecord.topic());
            acknowledgment.acknowledge();
            return;
        }
        try {
            messageIndexingReceiver.messageReceived(consumerRecord.value());
            acknowledgment.acknowledge();
        } catch (IndexWriterException iwe) {
            throw createMessageHandlerException(iwe, iwe.isRetryable() ? Temporality.TEMPORARY : Temporality.PERMANENT);
        } catch (Exception ex) {
            throw createMessageHandlerException(ex, Temporality.TEMPORARY);
        }
    }

    private MessageHandlerException createMessageHandlerException(Exception ex, Temporality temporality) {
        return MessageHandlerException.builder()
                .message("Exception while processing index writer message")
                .errorCode(ERROR_CODE)
                .temporality(temporality)
                .message(ex.getMessage())
                .cause(ex)
                .build();
    }
}
