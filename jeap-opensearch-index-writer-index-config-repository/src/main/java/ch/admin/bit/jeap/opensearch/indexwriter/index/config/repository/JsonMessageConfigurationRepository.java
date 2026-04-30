package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.*;

@Repository
@Slf4j
public class JsonMessageConfigurationRepository implements MessageConfigurationRepository {

    public static final String OPENSEARCH_MESSAGES_JSON = "classpath:/opensearch/messages.json";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    private final IndexingConditionRepository indexingConditionRepository;
    private final ReferenceProviderRepository referenceProviderRepository;
    private final Environment environment;
    private final String messagesLocation;

    private final Map<String, MessageConfig> messagesByName = new HashMap<>();

    @Autowired
    JsonMessageConfigurationRepository(
            IndexingConditionRepository indexingConditionRepository,
            ReferenceProviderRepository referenceProviderRepository,
            Environment environment) {
        this(indexingConditionRepository, referenceProviderRepository, environment, OPENSEARCH_MESSAGES_JSON);
    }

    // for testing
    protected JsonMessageConfigurationRepository(
            IndexingConditionRepository indexingConditionRepository,
            ReferenceProviderRepository referenceProviderRepository,
            Environment environment,
            String messagesLocation) {
        this.indexingConditionRepository = indexingConditionRepository;
        this.referenceProviderRepository = referenceProviderRepository;
        this.environment = environment;
        this.messagesLocation = messagesLocation;
    }

    @Override
    public List<MessageConfig> getAll() {
        return List.copyOf(messagesByName.values());
    }

    public Optional<MessageConfig> findByName(String messageName) {
        return Optional.ofNullable(messagesByName.get(messageName));
    }

    @PostConstruct
    void load() throws IOException {
        load(messagesLocation);
    }

    void load(String location) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource = resolver.getResource(location);
        if (!resource.exists()) {
            throw ConfigurationException.noMessageConfigurationFound(location);
        }

        List<MessageConfigRaw> messages = readConfiguration(resource);
        addConfiguration(messages);
    }

    private static List<MessageConfigRaw> readConfiguration(Resource resource) throws IOException {
        List<MessageConfigRaw> messages;
        try (var inputStream = resource.getInputStream()) {
            log.info("Loading index writer message configuration from {}", resource.getDescription());
            MessagesConfiguration config = JSON_MAPPER.readValue(inputStream, MessagesConfiguration.class);
            messages = config.messages() != null ? config.messages() : List.of();
        }
        return messages;
    }

    private void addConfiguration(List<MessageConfigRaw> messages) {
        for (MessageConfigRaw message : messages) {
            List<MessageOperationConfig> operations = new ArrayList<>();
            for (MessageOperationConfigRaw operationConfigRaw : message.operations()) {
                validateRequiredFields(message.messageName(), operationConfigRaw);
                IndexingCondition<Message> condition = indexingConditionRepository.getIndexingCondition(operationConfigRaw.condition());
                ReferenceProvider<Message> referenceProvider = referenceProviderRepository.getReferenceProvider(message.messageName(), operationConfigRaw.referenceProvider());
                var operationConfig = new MessageOperationConfig(
                        operationConfigRaw.indexType(),
                        operationConfigRaw.indexOperation(),
                        environment.resolvePlaceholders(operationConfigRaw.uri()),
                        referenceProvider,
                        condition,
                        operationConfigRaw.featureFlag());
                operations.add(operationConfig);
            }

            var messageConfig = new MessageConfig(
                    message.messageName(),
                    message.topicName(),
                    operations);
            messagesByName.put(message.messageName(), messageConfig);
        }
    }


    private static void validateRequiredFields(String messageName, MessageOperationConfigRaw operationConfigRaw) {
        List<String> missingFields = new ArrayList<>();
        if (operationConfigRaw.indexType() == null) {
            missingFields.add("indexType");
        }
        if (operationConfigRaw.indexOperation() == null) {
            missingFields.add("indexOperation");
        }
        if (operationConfigRaw.uri() == null) {
            missingFields.add("uri");
        }
        if (operationConfigRaw.referenceProvider() == null) {
            missingFields.add("referenceProvider");
        }
        if (!missingFields.isEmpty()) {
            throw ConfigurationException.requiredOperationFieldsMissing(messageName, missingFields);
        }
    }

    private record MessagesConfiguration(List<MessageConfigRaw> messages) {
    }
}