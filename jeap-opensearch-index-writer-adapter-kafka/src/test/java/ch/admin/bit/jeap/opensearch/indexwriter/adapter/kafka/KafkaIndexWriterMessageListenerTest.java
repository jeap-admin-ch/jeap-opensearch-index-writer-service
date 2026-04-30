package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.messaging.avro.AvroMessage;
import ch.admin.bit.jeap.messaging.avro.AvroMessageKey;
import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.messaging.avro.errorevent.MessageHandlerException;
import ch.admin.bit.jeap.messaging.avro.errorevent.MessageHandlerExceptionInformation.Temporality;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.MessageIndexingReceiver;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaIndexWriterMessageListenerTest {

    @Mock
    private MessageIndexingReceiver messageIndexingReceiver;

    @Mock
    private Acknowledgment acknowledgment;

    private KafkaIndexWriterMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new KafkaIndexWriterMessageListener(
                Set.of("PreziusRegistrationCreated"), messageIndexingReceiver);
    }

    @Test
    void knownMessageIsForwardedToReceiverAndAcknowledged() {
        listener.onMessage(consumerRecord("PreziusRegistrationCreated"), acknowledgment);

        verify(messageIndexingReceiver).messageReceived(any(AvroMessage.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void unknownMessageIsIgnoredAndAcknowledged() {
        listener.onMessage(consumerRecord("SomeOtherEvent"), acknowledgment);

        verifyNoInteractions(messageIndexingReceiver);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void genericExceptionIsWrappedAsTemporaryMessageHandlerException() {
        doThrow(new RuntimeException("indexing failed"))
                .when(messageIndexingReceiver).messageReceived(any());
        var consumerRecord = consumerRecord("PreziusRegistrationCreated");

        assertThatThrownBy(() -> listener.onMessage(consumerRecord, acknowledgment))
                .isInstanceOf(MessageHandlerException.class)
                .satisfies(ex -> {
                    MessageHandlerException mhe = (MessageHandlerException) ex;
                    assertThat(mhe.getTemporality()).isEqualTo(Temporality.TEMPORARY);
                    assertThat(mhe.getErrorCode()).isEqualTo("index-writer-failed");
                });
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void retryableIndexWriterExceptionIsWrappedAsTemporaryMessageHandlerException() {
        doThrow(new TestIndexWriterException("retryable failure", true))
                .when(messageIndexingReceiver).messageReceived(any());
        var consumerRecord = consumerRecord("PreziusRegistrationCreated");

        assertThatThrownBy(() -> listener.onMessage(consumerRecord, acknowledgment))
                .isInstanceOf(MessageHandlerException.class)
                .satisfies(ex -> {
                    MessageHandlerException mhe = (MessageHandlerException) ex;
                    assertThat(mhe.getTemporality()).isEqualTo(Temporality.TEMPORARY);
                    assertThat(mhe.getErrorCode()).isEqualTo("index-writer-failed");
                });
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void nonRetryableIndexWriterExceptionIsWrappedAsPermanentMessageHandlerException() {
        doThrow(new TestIndexWriterException("permanent failure", false))
                .when(messageIndexingReceiver).messageReceived(any());
        var consumerRecord = consumerRecord("PreziusRegistrationCreated");

        assertThatThrownBy(() -> listener.onMessage(consumerRecord, acknowledgment))
                .isInstanceOf(MessageHandlerException.class)
                .satisfies(ex -> {
                    MessageHandlerException mhe = (MessageHandlerException) ex;
                    assertThat(mhe.getTemporality()).isEqualTo(Temporality.PERMANENT);
                    assertThat(mhe.getErrorCode()).isEqualTo("index-writer-failed");
                });
        verifyNoInteractions(acknowledgment);
    }

    private ConsumerRecord<AvroMessageKey, AvroMessage> consumerRecord(String typeName) {
        AvroMessage message = mock(AvroMessage.class);
        AvroMessageType messageType = AvroMessageType.newBuilder()
                .setName(typeName)
                .setVersion("1.0.0")
                .build();
        when(message.getType()).thenReturn(messageType);
        return new ConsumerRecord<>("some-topic", 0, 0, null, message);
    }

    private static class TestIndexWriterException extends IndexWriterException {
        TestIndexWriterException(String message, boolean retryable) {
            super(message, retryable);
        }
    }
}
