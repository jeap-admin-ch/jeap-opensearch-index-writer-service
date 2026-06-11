package ch.admin.bit.jeap.opensearch.client.config;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenSearchClientConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OpenSearchClientConfiguration.class));

    @Test
    void withUriPropertyAndNoAwsRegion_singleOpenSearchClientBeanIsContributed() {
        runner.withPropertyValues("jeap.opensearch.client.connection.uri=http://localhost:9200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context).hasSingleBean(OpenSearchClientConfigurationProperties.class);
                });
    }

    @Test
    void userProvidedOpenSearchClient_shadowsAutoConfiguredDefault() {
        OpenSearchClient custom = mock(OpenSearchClient.class);

        runner.withPropertyValues("jeap.opensearch.client.connection.uri=http://localhost:9200")
                .withBean(OpenSearchClient.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context.getBean(OpenSearchClient.class)).isSameAs(custom);
                });
    }

    @Test
    void invalidUri_contextFailsWithIllegalStateExceptionInCauseChain() {
        // Walks the cause chain because Spring wraps the factory-method exception in BeanCreationException.
        runner.withPropertyValues("jeap.opensearch.client.connection.uri=ht tp://invalid")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(causeChain(failure))
                            .hasAtLeastOneElementOfType(IllegalStateException.class);
                });
    }

    private static java.util.List<Throwable> causeChain(Throwable t) {
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        Throwable current = t;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

}
