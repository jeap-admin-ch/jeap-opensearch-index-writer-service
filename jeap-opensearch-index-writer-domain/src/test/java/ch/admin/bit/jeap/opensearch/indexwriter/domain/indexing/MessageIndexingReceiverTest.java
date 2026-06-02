package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.messaging.model.MessageType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageIndexingReceiverTest {

    @Mock
    private MessageConfigurationRepository configurationRepository;

    @Mock
    private MessageIndexingService indexingService;

    @InjectMocks
    private MessageIndexingReceiver receiver;

    @Mock
    private Message message;

    @BeforeEach
    void setUp() {
        MessageType type = mock(MessageType.class);
        when(message.getType()).thenReturn(type);
        when(type.getName()).thenReturn("PreziusRegistrationCreated");
    }

    @Test
    void dispatchesSingleOperationToIndexingService() {
        MessageOperationConfig op = operation(IndexOperation.UPSERT, "PreziusRegistration");
        when(configurationRepository.findByName("PreziusRegistrationCreated"))
                .thenReturn(Optional.of(new MessageConfig("PreziusRegistrationCreated", "topic", List.of(op))));

        receiver.messageReceived(message);

        verify(indexingService).index(message, op);
    }

    @Test
    void dispatchesAllOperationsWhenMultipleConfigured() {
        MessageOperationConfig upsert = operation(IndexOperation.UPSERT, "PreziusRegistration");
        MessageOperationConfig delete = operation(IndexOperation.DELETE, "OtherType");
        when(configurationRepository.findByName("PreziusRegistrationCreated"))
                .thenReturn(Optional.of(new MessageConfig("PreziusRegistrationCreated", "topic", List.of(upsert, delete))));

        receiver.messageReceived(message);

        verify(indexingService).index(message, upsert);
        verify(indexingService).index(message, delete);
    }

    @Test
    void throwsForUnknownMessageType() {
        when(configurationRepository.findByName("PreziusRegistrationCreated"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiver.messageReceived(message))
                .isInstanceOf(IndexingException.class)
                .hasMessageContaining("PreziusRegistrationCreated");
    }

    private MessageOperationConfig operation(IndexOperation op, String indexType) {
        return new MessageOperationConfig(indexType, op, null, null, null, null, null);
    }
}
