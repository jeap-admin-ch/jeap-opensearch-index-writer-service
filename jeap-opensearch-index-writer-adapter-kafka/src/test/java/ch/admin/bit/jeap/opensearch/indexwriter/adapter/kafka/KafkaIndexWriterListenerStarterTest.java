package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaIndexWriterListenerStarterTest {

    @Mock
    private MessageConfigurationRepository repository;

    @Mock
    private KafkaIndexWriterConsumerFactory consumerFactory;

    @InjectMocks
    private KafkaIndexWriterListenerStarter starter;

    @Test
    void noConsumersStartedWhenNoConfigurationsFound() {
        when(repository.getAll()).thenReturn(List.of());

        starter.start();

        verifyNoInteractions(consumerFactory);
    }

    @Test
    void oneConsumerStartedPerTopic() {
        when(repository.getAll()).thenReturn(List.of(
                config("MessageA", "topic-a"),
                config("MessageB", "topic-b")));

        starter.start();

        verify(consumerFactory, times(2)).startConsumer(any(), any());
        verify(consumerFactory).startConsumer("topic-a", Set.of("MessageA"));
        verify(consumerFactory).startConsumer("topic-b", Set.of("MessageB"));
    }

    @Test
    void messagesOnSameTopicGroupedIntoSingleConsumer() {
        when(repository.getAll()).thenReturn(List.of(
                config("MessageA", "shared-topic"),
                config("MessageB", "shared-topic"),
                config("MessageC", "other-topic")));

        starter.start();

        verify(consumerFactory, times(2)).startConsumer(any(), any());

        ArgumentCaptor<Set<String>> namesCaptor = ArgumentCaptor.captor();
        verify(consumerFactory).startConsumer(eq("shared-topic"), namesCaptor.capture());
        assertThat(namesCaptor.getValue()).containsExactlyInAnyOrder("MessageA", "MessageB");
    }

    private MessageConfig config(String messageName, String topicName) {
        return new MessageConfig(messageName, topicName, List.of());
    }
}
