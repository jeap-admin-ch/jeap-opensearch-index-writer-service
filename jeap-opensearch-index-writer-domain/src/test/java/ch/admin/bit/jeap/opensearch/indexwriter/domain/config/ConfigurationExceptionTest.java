package ch.admin.bit.jeap.opensearch.indexwriter.domain.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationExceptionTest {

    @Test
    void noMessageConfigurationFound() {
        ConfigurationException ex = ConfigurationException.noMessageConfigurationFound("/some/path");
        assertThat(ex).hasMessageContaining("/some/path");
    }

    @Test
    void noIndexTypeFound() {
        ConfigurationException ex = ConfigurationException.noIndexTypeFound();
        assertThat(ex).hasMessageContaining("IndexType");
    }

    @Test
    void indexingConditionNotFound() {
        ConfigurationException ex = ConfigurationException.indexingConditionNotFound("MyCondition");
        assertThat(ex).hasMessageContaining("MyCondition");
    }

    @Test
    void referenceProviderNotFound() {
        ConfigurationException ex = ConfigurationException.referenceProviderNotFound("MyProvider");
        assertThat(ex).hasMessageContaining("MyProvider");
    }

    @Test
    void referenceProviderNotConfigured() {
        ConfigurationException ex = ConfigurationException.referenceProviderNotConfigured("MyMessage");
        assertThat(ex).hasMessageContaining("MyMessage");
    }
}
