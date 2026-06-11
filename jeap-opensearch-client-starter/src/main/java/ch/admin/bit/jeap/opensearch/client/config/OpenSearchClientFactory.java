package ch.admin.bit.jeap.opensearch.client.config;

import org.opensearch.client.opensearch.OpenSearchClient;
import tools.jackson.databind.json.JsonMapper;

interface OpenSearchClientFactory {

    OpenSearchClient createOpenSearchClient(OpenSearchClientConfigurationProperties properties,
                                            JsonMapper jsonMapper);
}
