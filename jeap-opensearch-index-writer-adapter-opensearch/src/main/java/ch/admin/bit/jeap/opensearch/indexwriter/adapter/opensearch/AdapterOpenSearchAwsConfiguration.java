package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(before = AdapterOpenSearchConfiguration.class)
@ConditionalOnClass(UrlConnectionHttpClient.class)
@ConditionalOnProperty(prefix = "jeap.opensearch.indexwriter.connection", name = "signing-region")
@EnableConfigurationProperties(AdapterOpenSearchProperties.class)
public class AdapterOpenSearchAwsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(AdapterOpenSearchProperties properties, JsonMapper jsonMapper) {
        JsonMapper openSearchMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        var httpClient = UrlConnectionHttpClient.builder().build();
        return new OpenSearchClient(
                new AwsSdk2Transport(
                        httpClient,
                        properties.connectionUrl().hostAndPort(),
                        "es",
                        Region.of(properties.getSigningRegion()),
                        AwsSdk2TransportOptions.builder()
                                .setCredentials(DefaultCredentialsProvider.builder().build())
                                .setMapper(new JacksonJsonpMapper(openSearchMapper))
                                .build()
                )
        );
    }
}
