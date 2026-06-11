package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.SearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserSearchItemAuthorization;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
public class SearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SearchItemClient searchItemClient(
            OpenSearchClient openSearchClient,
            JsonMapper jsonMapper,
            IndexTypeAuthorization indexTypeAuthorization,
            SearchItemAuthorization searchItemAuthorization,
            UserSearchItemAuthorization userSearchItemAuthorization) {
        return new SearchItemClient(openSearchClient, jsonMapper, indexTypeAuthorization,
                searchItemAuthorization, userSearchItemAuthorization);
    }
}
