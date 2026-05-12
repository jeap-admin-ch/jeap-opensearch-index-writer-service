package ch.admin.bit.jeap.opensearch.client.authprovider;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserAuthorizationProvider;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JeapSecurityAuthorizationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JeapSecurityAuthorizationAutoConfiguration.class));

    @Test
    void withJeapAuthenticationTokenOnClasspath_singleJeapSecurityProviderIsContributed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UserAuthorizationProvider.class);
            assertThat(context.getBean(UserAuthorizationProvider.class))
                    .isInstanceOf(JeapSecurityUserAuthorizationProvider.class);
        });
    }

    @Test
    void withCustomUserAuthorizationProvider_defaultIsNotCreated() {
        UserAuthorizationProvider custom = () -> new Authorization(Set.of("read"), Map.of());

        runner.withBean(UserAuthorizationProvider.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UserAuthorizationProvider.class);
                    assertThat(context.getBean(UserAuthorizationProvider.class)).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(JeapSecurityUserAuthorizationProvider.class);
                });
    }

    @Test
    void withoutJeapAuthenticationTokenClass_autoConfigIsSkippedEntirely() {
        runner.withClassLoader(new FilteredClassLoader(JeapAuthenticationToken.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(UserAuthorizationProvider.class);
                    assertThat(context).doesNotHaveBean(JeapSecurityUserAuthorizationProvider.class);
                });
    }
}
