package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.SearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserSearchItemAuthorization;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SearchAutoConfiguration.class));

    @Test
    void allMandatoryBeansPresent_searchItemClientBeanIsContributed() {
        runner.withUserConfiguration(MandatoryBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SearchItemClient.class);
                });
    }

    @Test
    void userSearchItemAuthorizationMissing_contextFailsToStart() {
        runner.withUserConfiguration(MandatoryBeansConfigWithoutUserAuth.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userProvidedSearchItemClient_shadowsAutoConfiguredDefault() {
        UserSearchItemAuthorization usia =
                new UserSearchItemAuthorization(null, new SearchItemAuthorization());
        SearchItemClient custom = new SearchItemClient(
                mock(OpenSearchClient.class),
                new JsonMapper(),
                new IndexTypeAuthorization(),
                new SearchItemAuthorization(),
                usia);

        runner.withUserConfiguration(MandatoryBeansConfig.class)
                .withBean(SearchItemClient.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SearchItemClient.class);
                    assertThat(context.getBean(SearchItemClient.class)).isSameAs(custom);
                });
    }

    @Configuration
    static class MandatoryBeansConfig {
        @Bean
        OpenSearchClient openSearchClient() {
            return mock(OpenSearchClient.class);
        }

        @Bean
        JsonMapper jsonMapper() {
            return new JsonMapper();
        }

        @Bean
        IndexTypeAuthorization indexTypeAuthorization() {
            return new IndexTypeAuthorization();
        }

        @Bean
        SearchItemAuthorization searchItemAuthorization() {
            return new SearchItemAuthorization();
        }

        @Bean
        UserSearchItemAuthorization userSearchItemAuthorization(SearchItemAuthorization sia) {
            return new UserSearchItemAuthorization(null, sia);
        }
    }

    @Configuration
    static class MandatoryBeansConfigWithoutUserAuth {
        @Bean
        OpenSearchClient openSearchClient() {
            return mock(OpenSearchClient.class);
        }

        @Bean
        JsonMapper jsonMapper() {
            return new JsonMapper();
        }

        @Bean
        IndexTypeAuthorization indexTypeAuthorization() {
            return new IndexTypeAuthorization();
        }

        @Bean
        SearchItemAuthorization searchItemAuthorization() {
            return new SearchItemAuthorization();
        }
    }
}
