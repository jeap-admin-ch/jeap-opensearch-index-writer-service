package ch.admin.bit.jeap.opensearch.client.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchClientConfigurationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnablePropertiesConfig.class);

    @Test
    void binding_populatesUriAndAwsSigningRegion() {
        runner.withPropertyValues(
                        "jeap.opensearch.client.connection.uri=http://localhost:9200",
                        "jeap.opensearch.client.connection.aws-signing-region=eu-central-2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenSearchClientConfigurationProperties props =
                            context.getBean(OpenSearchClientConfigurationProperties.class);
                    assertThat(props.getUri()).isEqualTo("http://localhost:9200");
                    assertThat(props.getAwsSigningRegion()).isEqualTo("eu-central-2");
                });
    }

    @Test
    void kebabCaseProperty_bindsToCamelCaseField() {
        runner.withPropertyValues(
                        "jeap.opensearch.client.connection.aws-signing-region=eu-west-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenSearchClientConfigurationProperties props =
                            context.getBean(OpenSearchClientConfigurationProperties.class);
                    assertThat(props.getAwsSigningRegion()).isEqualTo("eu-west-1");
                    assertThat(props.getUri()).isNull();
                });
    }

    @EnableConfigurationProperties(OpenSearchClientConfigurationProperties.class)
    static class EnablePropertiesConfig {
    }
}
