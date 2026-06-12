package ch.admin.bit.jeap.opensearch.client.config;

import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@ConditionalOnClass({UrlConnectionHttpClient.class, DefaultCredentialsProvider.class, Region.class})
@ConditionalOnProperty(prefix = "jeap.opensearch.client.connection", name = "aws-signing-region")
public class OpenSearchClientAwsConfiguration {

    @Bean
    @ConditionalOnMissingBean(OpenSearchClientFactory.class)
    OpenSearchClientFactory awsOpenSearchClientFactory() {
        return (properties, jsonMapper) -> {
            JsonMapper openSearchMapper = jsonMapper.rebuild()
                    .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
            var httpClient = UrlConnectionHttpClient.builder().build();
            return new OpenSearchClient(
                    new AwsSdk2Transport(
                            httpClient,
                            properties.getUri(),
                            "es",
                            Region.of(properties.getAwsSigningRegion()),
                            AwsSdk2TransportOptions.builder()
                                    .setCredentials(DefaultCredentialsProvider.builder().build())
                                    .setMapper(new JacksonJsonpMapper(openSearchMapper))
                                    .build()
                    )
            );
        };
    }
}
