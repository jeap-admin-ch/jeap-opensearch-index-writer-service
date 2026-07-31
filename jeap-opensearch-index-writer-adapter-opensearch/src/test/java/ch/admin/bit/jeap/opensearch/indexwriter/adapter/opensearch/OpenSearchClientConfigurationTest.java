package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchClientConfigurationTest {

    private final ApplicationContextRunner awsContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdapterOpenSearchAwsConfiguration.class))
            .withUserConfiguration(JsonMapperConfiguration.class);

    @Test
    void awsTransport_receivesHostWithoutScheme() {
        awsContextRunner
                .withPropertyValues(
                        "jeap.opensearch.indexwriter.connection.url=https://my-domain.eu-central-2.es.amazonaws.com",
                        "jeap.opensearch.indexwriter.connection.signing-region=eu-central-2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenSearchClient client = context.getBean(OpenSearchClient.class);
                    assertThat(client._transport()).isInstanceOf(AwsSdk2Transport.class);
                    // The AWS transport prefixes the configured value with 'https://' itself, a scheme would result
                    // in an unresolvable host name such as 'https'
                    assertThat(hostOf((AwsSdk2Transport) client._transport()))
                            .isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
                });
    }

    @Test
    void awsTransport_receivesHostAndPortWithoutScheme() {
        awsContextRunner
                .withPropertyValues(
                        "jeap.opensearch.indexwriter.connection.url=https://my-opensearch-host:9200",
                        "jeap.opensearch.indexwriter.connection.signing-region=eu-central-2")
                .run(context -> assertThat(hostOf((AwsSdk2Transport) context.getBean(OpenSearchClient.class)._transport()))
                        .isEqualTo("my-opensearch-host:9200"));
    }

    @Test
    void invalidUrl_failsStartupWithConfigurationHint() {
        awsContextRunner
                .withPropertyValues(
                        "jeap.opensearch.indexwriter.connection.url=ftp://my-domain",
                        "jeap.opensearch.indexwriter.connection.signing-region=eu-central-2")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(OpenSearchIndexWriterException.class)
                        .hasMessageContaining("jeap.opensearch.indexwriter.connection.url")
                        .hasMessageContaining("unsupported scheme 'ftp'"));
    }

    @Test
    void withoutSigningRegion_noAwsClientIsCreated() {
        awsContextRunner
                .withPropertyValues("jeap.opensearch.indexwriter.connection.url=http://localhost:9200")
                .run(context -> assertThat(context).doesNotHaveBean(OpenSearchClient.class));
    }

    @Test
    void apacheTransport_isCreatedForNonAwsUrl() {
        AdapterOpenSearchProperties properties = new AdapterOpenSearchProperties();
        properties.setUrl("http://localhost:9200");
        properties.validate();

        OpenSearchClient client = new AdapterOpenSearchConfiguration()
                .openSearchClient(properties, JsonMapper.builder().build());

        assertThat(client._transport()).isInstanceOf(ApacheHttpClient5Transport.class);
    }

    private static String hostOf(AwsSdk2Transport transport) throws Exception {
        Field hostField = AwsSdk2Transport.class.getDeclaredField("host");
        hostField.setAccessible(true);
        return (String) hostField.get(transport);
    }

    @Configuration(proxyBeanMethods = false)
    static class JsonMapperConfiguration {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
