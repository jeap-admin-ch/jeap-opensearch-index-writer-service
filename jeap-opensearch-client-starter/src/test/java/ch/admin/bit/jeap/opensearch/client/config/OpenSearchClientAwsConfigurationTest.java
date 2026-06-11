package ch.admin.bit.jeap.opensearch.client.config;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Note: these tests don't open an AWS connection — {@code Region.of(...)} is pure and
 * {@link AwsSdk2Transport}'s constructor does not connect eagerly.
 */
class OpenSearchClientAwsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OpenSearchClientConfiguration.class,
                    OpenSearchClientAwsConfiguration.class));

    @Test
    void withAwsSigningRegionAndUrlConnectionClientOnClasspath_awsBackedClientIsContributed() {
        runner.withPropertyValues(
                        "jeap.opensearch.client.connection.uri=https://search-foo.eu-central-2.es.amazonaws.com",
                        "jeap.opensearch.client.connection.aws-signing-region=eu-central-2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    OpenSearchClient client = context.getBean(OpenSearchClient.class);
                    assertThat(client._transport()).isInstanceOf(AwsSdk2Transport.class);
                });
    }

    @Test
    void withoutAwsSigningRegion_awsConfigDoesNotEngage_defaultPathIsUsed() {
        runner.withPropertyValues(
                        "jeap.opensearch.client.connection.uri=http://localhost:9200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    OpenSearchClient client = context.getBean(OpenSearchClient.class);
                    assertThat(client._transport()).isNotInstanceOf(AwsSdk2Transport.class);
                });
    }

    @Test
    void withoutUrlConnectionHttpClientOnClasspath_awsConfigIsSkipped_defaultPathIsUsed() {
        runner.withClassLoader(new FilteredClassLoader(UrlConnectionHttpClient.class))
                .withPropertyValues(
                        "jeap.opensearch.client.connection.uri=http://localhost:9200",
                        "jeap.opensearch.client.connection.aws-signing-region=eu-central-2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    OpenSearchClient client = context.getBean(OpenSearchClient.class);
                    assertThat(client._transport()).isNotInstanceOf(AwsSdk2Transport.class);
                });
    }

    @Test
    void bothConfigsRegistered_awsFactoryIsUsedByCentralClientConfiguration() {
        runner.withPropertyValues(
                        "jeap.opensearch.client.connection.uri=https://search-foo.eu-central-2.es.amazonaws.com",
                        "jeap.opensearch.client.connection.aws-signing-region=eu-central-2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context).hasSingleBean(OpenSearchClientFactory.class);
                    OpenSearchClient client = context.getBean(OpenSearchClient.class);
                    assertThat(client._transport()).isInstanceOf(AwsSdk2Transport.class);
                });
    }
}
