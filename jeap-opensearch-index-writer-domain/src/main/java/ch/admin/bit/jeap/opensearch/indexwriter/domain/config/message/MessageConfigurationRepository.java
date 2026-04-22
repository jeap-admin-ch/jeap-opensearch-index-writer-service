package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message;

import java.util.List;
import java.util.Optional;

public interface MessageConfigurationRepository {

    List<MessageConfig> getAll();

    Optional<MessageConfig> findByName(String messageName);
}
