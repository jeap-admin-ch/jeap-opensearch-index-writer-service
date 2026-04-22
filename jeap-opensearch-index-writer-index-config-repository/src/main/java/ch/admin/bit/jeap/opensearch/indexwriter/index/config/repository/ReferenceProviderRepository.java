package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

@Repository
@Slf4j
public class ReferenceProviderRepository {

    private final Map<String, ReferenceProvider<Message>> referenceProvidersByName;

    public ReferenceProviderRepository(List<ReferenceProvider<Message>> referenceProviders) {
        this.referenceProvidersByName = referenceProviders.stream()
                .collect(toMap(c -> c.getClass().getSimpleName(), c -> c));
        log.info("Loaded {} reference provider(s): {}", referenceProviders.size(), referenceProvidersByName.keySet());
    }

    ReferenceProvider<Message> getReferenceProvider(String messageName, String referenceProviderName) {
        if (referenceProviderName == null) {
            throw ConfigurationException.referenceProviderNotConfigured(messageName);
        }
        ReferenceProvider<Message> referenceProvider = referenceProvidersByName.get(referenceProviderName);
        if (referenceProvider == null) {
            throw ConfigurationException.referenceProviderNotFound(referenceProviderName);
        }

        return referenceProvider;
    }
}
