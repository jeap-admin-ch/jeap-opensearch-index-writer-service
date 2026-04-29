package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.condition.IndexingCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingConditionRepositoryTest {

    static class ActiveCondition implements IndexingCondition<Message> {
        @Override
        public boolean evaluate(Message message) {
            return true;
        }
    }

    static class InactiveCondition implements IndexingCondition<Message> {
        @Override
        public boolean evaluate(Message message) {
            return false;
        }
    }

    private final ActiveCondition activeCondition = new ActiveCondition();
    private final InactiveCondition inactiveCondition = new InactiveCondition();

    private final IndexingConditionRepository repository =
            new IndexingConditionRepository(List.of(activeCondition, inactiveCondition));

    @Test
    void getIndexingCondition_returnsByFqn() {
        assertThat(repository.getIndexingCondition(ActiveCondition.class.getName())).isSameAs(activeCondition);
        assertThat(repository.getIndexingCondition(InactiveCondition.class.getName())).isSameAs(inactiveCondition);
    }

    @Test
    void getIndexingCondition_returnsNullWhenConditionNameIsNull() {
        assertThat(repository.getIndexingCondition(null)).isNull();
    }

    @Test
    void getIndexingCondition_throwsWhenConditionNotFound() {
        assertThatThrownBy(() -> repository.getIndexingCondition("UnknownCondition"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("UnknownCondition");
    }
}
