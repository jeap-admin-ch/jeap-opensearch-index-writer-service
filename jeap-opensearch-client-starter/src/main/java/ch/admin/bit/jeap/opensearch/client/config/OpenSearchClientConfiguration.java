package ch.admin.bit.jeap.opensearch.client.config;

import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URISyntaxException;

@AutoConfiguration
@EnableConfigurationProperties(OpenSearchClientConfigurationProperties.class)
public class OpenSearchClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(OpenSearchClientConfigurationProperties properties,
                                      JsonMapper jsonMapper,
                                      ObjectProvider<OpenSearchClientFactory> clientFactory) {
        return clientFactory
                .getIfAvailable(() -> this::createDefaultOpenSearchClient)
                .createOpenSearchClient(properties, jsonMapper);
    }

    private OpenSearchClient createDefaultOpenSearchClient(OpenSearchClientConfigurationProperties properties,
                                                           JsonMapper jsonMapper) {
        try {
            JsonMapper openSearchMapper = jsonMapper.rebuild()
                    .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
            var transport = ApacheHttpClient5TransportBuilder
                    .builder(HttpHost.create(properties.getUri()))
                    .setMapper(new JacksonJsonpMapper(openSearchMapper))
                    .build();
            return new OpenSearchClient(transport);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid OpenSearch URL: " + properties.getUri(), e);
        }
    }
}
