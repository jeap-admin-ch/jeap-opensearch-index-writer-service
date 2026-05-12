package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.SearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserSearchItemAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class SearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SearchItemClient searchItemClient(
            OpenSearchClient openSearchClient,
            ObjectMapper objectMapper,
            IndexTypeAuthorization indexTypeAuthorization,
            SearchItemAuthorization searchItemAuthorization,
            UserSearchItemAuthorization userSearchItemAuthorization) {
        return new SearchItemClient(openSearchClient, objectMapper, indexTypeAuthorization,
                searchItemAuthorization, userSearchItemAuthorization);
    }
}
