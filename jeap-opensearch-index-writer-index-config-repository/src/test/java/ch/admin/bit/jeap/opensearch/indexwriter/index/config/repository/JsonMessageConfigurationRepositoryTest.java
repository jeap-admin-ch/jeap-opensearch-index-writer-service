package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JsonMessageConfigurationRepositoryTest {

    @Mock
    private IndexingConditionRepository indexingConditionRepository;
    @Mock
    private ReferenceProviderRepository referenceProviderRepository;
    @Mock
    private IndexingCondition<Message> registrationIsActiveCondition;
    @Mock
    private ReferenceProvider<Message> registrationReferenceProvider;

    private JsonMessageConfigurationRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        when(indexingConditionRepository.getIndexingCondition("ch.admin.bit.prezius.PreziusRegistrationIsActiveCondition"))
                .thenReturn(registrationIsActiveCondition);
        when(referenceProviderRepository.getReferenceProvider(any(), eq("ch.admin.bit.prezius.PreziusRegistrationReferenceProvider")))
                .thenReturn(registrationReferenceProvider);

        repository = new JsonMessageConfigurationRepository(indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());
        repository.load();
    }

    @Test
    void getAllReturnsAllConfiguredMessages() {
        List<MessageConfig> all = repository.getAll();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(MessageConfig::messageName)
                .containsExactlyInAnyOrder("PreziusRegistrationCreated", "PreziusRegistrationDeleted");
    }

    @Test
    void findByNameReturnsConfigForKnownMessage() {
        Optional<MessageConfig> result = repository.findByName("PreziusRegistrationCreated");

        assertThat(result).isPresent();
        MessageConfig config = result.get();
        assertThat(config.topicName()).isEqualTo("ch.admin.bit.prezius.registration-created");
        assertThat(config.operations()).hasSize(1);
        MessageOperationConfig op = config.operations().getFirst();
        assertThat(op.indexType()).isEqualTo("PreziusRegistration");
        assertThat(op.indexOperation()).isEqualTo(IndexOperation.UPSERT);
        assertThat(op.uri()).isEqualTo("https://prezius/api/v1/registrations/{id}");
        assertThat(op.referenceProvider()).isSameAs(registrationReferenceProvider);
        assertThat(op.condition()).isSameAs(registrationIsActiveCondition);
        assertThat(op.featureFlag()).isEqualTo("PREZIUS_REGISTRATION_INDEXING");
    }

    @Test
    void findByNameReturnsEmptyForUnknownMessage() {
        assertThat(repository.findByName("Unknown")).isEmpty();
    }

    @Test
    void optionalFieldsAreNullWhenAbsent() {
        Optional<MessageConfig> result = repository.findByName("PreziusRegistrationDeleted");

        assertThat(result).isPresent();
        MessageOperationConfig op = result.get().operations().getFirst();
        assertThat(op.indexOperation()).isEqualTo(IndexOperation.DELETE);
        assertThat(op.condition()).isNull();
        assertThat(op.featureFlag()).isNull();
    }

    @Test
    void indexOperationIsCaseInsensitive() {
        // The DELETE message uses lowercase "delete" in the JSON fixture
        assertThat(repository.findByName("PreziusRegistrationDeleted"))
                .isPresent()
                .map(c -> c.operations().getFirst().indexOperation())
                .hasValue(IndexOperation.DELETE);
    }

    @Test
    void failsToStartWhenNoFileOnClasspath() {
        var properties = new IndexConfigRepositoryProperties();
        properties.setMessagesLocation("classpath:/opensearch/nonexistent.json");
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                properties);

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/nonexistent.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("nonexistent.json");
    }

    @Test
    void failsToLoadWhenIndexTypeIsMissing() {
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/messages-missing-index-type.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("indexType")
                .hasMessageContaining("PreziusRegistrationCreated");
    }

    @Test
    void failsToLoadWhenIndexOperationIsMissing() {
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/messages-missing-index-operation.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("indexOperation")
                .hasMessageContaining("PreziusRegistrationCreated");
    }

    @Test
    void failsToLoadWhenUriIsMissing() {
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/messages-missing-uri.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("uri")
                .hasMessageContaining("PreziusRegistrationCreated");
    }

    @Test
    void failsToLoadWhenReferenceProviderIsMissing() {
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/messages-missing-reference-provider.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("referenceProvider")
                .hasMessageContaining("PreziusRegistrationCreated");
    }

    @Test
    void failsToLoadWhenMultipleRequiredFieldsAreMissing() {
        JsonMessageConfigurationRepository repo = new JsonMessageConfigurationRepository(
                indexingConditionRepository, referenceProviderRepository, new StandardEnvironment(),
                new IndexConfigRepositoryProperties());

        assertThatThrownBy(() -> repo.load("classpath:/opensearch/messages-missing-multiple-fields.json"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("indexType")
                .hasMessageContaining("uri")
                .hasMessageContaining("PreziusRegistrationCreated");
    }
}
