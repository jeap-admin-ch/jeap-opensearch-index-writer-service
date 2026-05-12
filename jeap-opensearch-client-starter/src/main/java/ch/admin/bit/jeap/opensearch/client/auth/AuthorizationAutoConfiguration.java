package ch.admin.bit.jeap.opensearch.client.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class AuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IndexTypeAuthorization indexTypeAuthorization() {
        return new IndexTypeAuthorization();
    }

    @Bean
    @ConditionalOnMissingBean
    SearchItemAuthorization searchItemAuthorization() {
        return new SearchItemAuthorization();
    }

    @Bean
    @ConditionalOnMissingBean
    UserSearchItemAuthorization userSearchItemAuthorization(
            ObjectProvider<UserAuthorizationProvider> userAuthorizationProvider,
            SearchItemAuthorization searchItemAuthorization) {
        return new UserSearchItemAuthorization(
                userAuthorizationProvider.getIfAvailable(),
                searchItemAuthorization);
    }
}
