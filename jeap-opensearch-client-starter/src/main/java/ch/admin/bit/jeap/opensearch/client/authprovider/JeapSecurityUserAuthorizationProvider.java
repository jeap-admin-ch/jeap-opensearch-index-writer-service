package ch.admin.bit.jeap.opensearch.client.authprovider;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserAuthorizationProvider;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Default {@link UserAuthorizationProvider} implementation that extracts the current user's roles
 * directly from the {@link JeapAuthenticationToken} in the {@link SecurityContextHolder}.
 */
public class JeapSecurityUserAuthorizationProvider implements UserAuthorizationProvider {

    @Override
    public Authorization getAuthorization() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null) {
            return null;
        }
        Authentication authentication = context.getAuthentication();
        if (!(authentication instanceof JeapAuthenticationToken jeapToken)
                || !jeapToken.isAuthenticated()) {
            return null;
        }
        return new Authorization(jeapToken.getUserRoles(), jeapToken.getBusinessPartnerRoles());
    }
}
