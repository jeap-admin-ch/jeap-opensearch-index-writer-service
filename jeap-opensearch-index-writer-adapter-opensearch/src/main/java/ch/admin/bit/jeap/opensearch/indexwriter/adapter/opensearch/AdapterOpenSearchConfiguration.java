package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch")
@EnableConfigurationProperties(AdapterOpenSearchProperties.class)
public class AdapterOpenSearchConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DataFieldValidator dataFieldValidator(ObjectMapper objectMapper) {
        return new DataFieldValidator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(AdapterOpenSearchProperties properties, ObjectMapper objectMapper) {
        try {
            var transport = ApacheHttpClient5TransportBuilder
                    .builder(HttpHost.create(properties.getUrl()))
                    .setMapper(new JacksonJsonpMapper(objectMapper))
                    .build();
            return new OpenSearchClient(transport);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Invalid OpenSearch URL: " + properties.getUrl(), e);
        }
    }
}
