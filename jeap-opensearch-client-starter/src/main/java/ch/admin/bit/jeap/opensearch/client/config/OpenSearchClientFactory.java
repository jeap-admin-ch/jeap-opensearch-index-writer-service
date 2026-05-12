package ch.admin.bit.jeap.opensearch.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;

interface OpenSearchClientFactory {

    OpenSearchClient createOpenSearchClient(OpenSearchClientConfigurationProperties properties,
                                            ObjectMapper objectMapper);
}
