package ch.admin.bit.jeap.opensearch.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.URISyntaxException;

@AutoConfiguration
@EnableConfigurationProperties(OpenSearchClientConfigurationProperties.class)
public class OpenSearchClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(OpenSearchClientConfigurationProperties properties,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<OpenSearchClientFactory> clientFactory) {
        return clientFactory
                .getIfAvailable(() -> this::createDefaultOpenSearchClient)
                .createOpenSearchClient(properties, objectMapper);
    }

    private OpenSearchClient createDefaultOpenSearchClient(OpenSearchClientConfigurationProperties properties,
                                                          ObjectMapper objectMapper) {
        try {
            var transport = ApacheHttpClient5TransportBuilder
                    .builder(HttpHost.create(properties.getUri()))
                    .setMapper(new JacksonJsonpMapper(objectMapper))
                    .build();
            return new OpenSearchClient(transport);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid OpenSearch URL: " + properties.getUri(), e);
        }
    }
}
