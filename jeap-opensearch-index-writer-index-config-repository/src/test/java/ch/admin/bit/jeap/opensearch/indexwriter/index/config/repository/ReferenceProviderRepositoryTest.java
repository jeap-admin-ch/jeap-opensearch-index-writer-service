package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import ch.admin.bit.jeap.messaging.model.Message;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.ReferenceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceProviderRepositoryTest {

    static class FooReferenceProvider implements ReferenceProvider<Message> {
        @Override
        public OriginReference extractReference(Message message) {
            return null;
        }
    }

    static class BarReferenceProvider implements ReferenceProvider<Message> {
        @Override
        public OriginReference extractReference(Message message) {
            return null;
        }
    }

    private final FooReferenceProvider fooProvider = new FooReferenceProvider();
    private final BarReferenceProvider barProvider = new BarReferenceProvider();

    private final ReferenceProviderRepository repository =
            new ReferenceProviderRepository(List.of(fooProvider, barProvider));

    @Test
    void getReferenceProvider_returnsByFqn() {
        String fooFqn = FooReferenceProvider.class.getName();
        String barFqn = BarReferenceProvider.class.getName();
        assertThat(repository.getReferenceProvider("SomeMessage", fooFqn)).isSameAs(fooProvider);
        assertThat(repository.getReferenceProvider("SomeMessage", barFqn)).isSameAs(barProvider);
    }

    @Test
    void getReferenceProvider_throwsWhenNameIsNull() {
        assertThatThrownBy(() -> repository.getReferenceProvider("SomeMessage", null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("SomeMessage");
    }

    @Test
    void getReferenceProvider_throwsWhenProviderNotFound() {
        assertThatThrownBy(() -> repository.getReferenceProvider("SomeMessage", "UnknownProvider"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("UnknownProvider");
    }
}
