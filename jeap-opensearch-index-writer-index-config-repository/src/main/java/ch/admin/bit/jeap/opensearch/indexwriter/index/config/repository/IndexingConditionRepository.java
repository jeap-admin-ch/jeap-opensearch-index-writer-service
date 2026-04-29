package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

@Repository
@Slf4j
public class IndexingConditionRepository {

    private final Map<String, IndexingCondition<Message>> conditionsByName;

    public IndexingConditionRepository(List<IndexingCondition<Message>> indexingConditions) {
        this.conditionsByName = indexingConditions.stream()
                .collect(toMap(c -> c.getClass().getName(), c -> c));
        log.info("Loaded {} indexing condition(s): {}", indexingConditions.size(), conditionsByName.keySet());
    }

    IndexingCondition<Message> getIndexingCondition(String conditionName) {
        IndexingCondition<Message> condition = null;
        if (conditionName != null) {
            condition = conditionsByName.get(conditionName);
            if (condition == null) {
                throw ConfigurationException.indexingConditionNotFound(conditionName);
            }
        }
        return condition;
    }
}
