package ch.admin.bit.jeap.opensearch.client.authprovider;

import ch.admin.bit.jeap.opensearch.client.auth.UserAuthorizationProvider;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Provides the default {@link UserAuthorizationProvider} backed by the
 * {@link JeapAuthenticationToken} from the security context.
 *
 * <p>Kept separate from the general authorization configuration so that the starter remains
 * fully usable without {@code jeap-spring-boot-security-starter} on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(JeapAuthenticationToken.class)
public class JeapSecurityAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(UserAuthorizationProvider.class)
    UserAuthorizationProvider jeapSecurityUserAuthorizationProvider() {
        return new JeapSecurityUserAuthorizationProvider();
    }
}
