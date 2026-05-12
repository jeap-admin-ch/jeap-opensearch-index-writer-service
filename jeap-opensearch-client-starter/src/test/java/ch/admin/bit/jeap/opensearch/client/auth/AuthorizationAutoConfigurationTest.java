package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.authprovider.JeapSecurityAuthorizationAutoConfiguration;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthorizationAutoConfiguration.class));

    @Test
    void default_withoutUserAuthorizationProvider_userSearchItemAuthorizationStillContributed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IndexTypeAuthorization.class);
            assertThat(context).hasSingleBean(SearchItemAuthorization.class);
            assertThat(context).hasSingleBean(UserSearchItemAuthorization.class);
            assertThat(context).doesNotHaveBean(UserAuthorizationProvider.class);
            UserSearchItemAuthorization usia = context.getBean(UserSearchItemAuthorization.class);
            assertThat(usia.getUserAuthorization()).isNull();
        });
    }

    @Test
    void withUserAuthorizationProviderBean_userSearchItemAuthorizationDelegatesToIt() {
        runner.withUserConfiguration(UserAuthorizationProviderTestConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IndexTypeAuthorization.class);
                    assertThat(context).hasSingleBean(SearchItemAuthorization.class);
                    assertThat(context).hasSingleBean(UserAuthorizationProvider.class);
                    assertThat(context).hasSingleBean(UserSearchItemAuthorization.class);

                    UserSearchItemAuthorization usia = context.getBean(UserSearchItemAuthorization.class);
                    Authorization auth = usia.getUserAuthorization();
                    assertThat(auth).isNotNull();
                    assertThat(auth.userroles()).containsExactly("read");
                });
    }

    @Test
    void withJeapSecurityAutoConfiguration_userSearchItemAuthorizationUsesAutoConfiguredProvider() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AuthorizationAutoConfiguration.class,
                        JeapSecurityAuthorizationAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserAuthorizationProvider.class);
                    assertThat(context).hasSingleBean(UserSearchItemAuthorization.class);

                    JeapAuthenticationToken token = mock(JeapAuthenticationToken.class);
                    when(token.isAuthenticated()).thenReturn(true);
                    when(token.getUserRoles()).thenReturn(Set.of("read"));
                    when(token.getBusinessPartnerRoles()).thenReturn(Map.of());

                    try {
                        setAuthentication(token);

                        Authorization auth = context.getBean(UserSearchItemAuthorization.class)
                                .getUserAuthorization();

                        assertThat(auth).isNotNull();
                        assertThat(auth.userroles()).containsExactly("read");
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
    }

    @Test
    void userProvidedSearchItemAuthorization_shadowsDefault() {
        SearchItemAuthorization custom = new SearchItemAuthorization();

        runner.withBean(SearchItemAuthorization.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SearchItemAuthorization.class);
                    assertThat(context.getBean(SearchItemAuthorization.class)).isSameAs(custom);
                    assertThat(context).hasSingleBean(IndexTypeAuthorization.class);
                    assertThat(context).hasSingleBean(UserSearchItemAuthorization.class);
                });
    }

    @Test
    void userProvidedIndexTypeAuthorization_shadowsDefault() {
        IndexTypeAuthorization custom = new IndexTypeAuthorization();

        runner.withBean(IndexTypeAuthorization.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IndexTypeAuthorization.class);
                    assertThat(context.getBean(IndexTypeAuthorization.class)).isSameAs(custom);
                });
    }

    @Test
    void userProvidedUserSearchItemAuthorization_shadowsDefault() {
        UserAuthorizationProvider provider = () -> new Authorization(Set.of("read"), Map.of());
        SearchItemAuthorization sia = new SearchItemAuthorization();
        UserSearchItemAuthorization custom = new UserSearchItemAuthorization(provider, sia);

        runner.withBean(UserAuthorizationProvider.class, () -> provider)
                .withBean(UserSearchItemAuthorization.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserSearchItemAuthorization.class);
                    assertThat(context.getBean(UserSearchItemAuthorization.class)).isSameAs(custom);
                });
    }

    @Configuration
    static class UserAuthorizationProviderTestConfig {
        @Bean
        UserAuthorizationProvider userAuthorizationProvider() {
            return () -> new Authorization(Set.of("read"), Map.of());
        }
    }

    private static void setAuthentication(Authentication authentication) {
        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
