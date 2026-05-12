package ch.admin.bit.jeap.opensearch.client.authprovider;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JeapSecurityUserAuthorizationProviderTest {

    private final JeapSecurityUserAuthorizationProvider sut = new JeapSecurityUserAuthorizationProvider();

    @AfterEach
    void clearSecurityContext() {
        // Never leak SecurityContext state into other tests.
        SecurityContextHolder.clearContext();
    }

    @Test
    void getAuthorization_noSecurityContextAuthentication_returnsNull() {
        SecurityContextHolder.clearContext();

        assertThat(sut.getAuthorization()).isNull();
    }

    @Test
    void getAuthorization_anonymousAuthentication_returnsNull() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        setAuthentication(anonymous);

        assertThat(sut.getAuthorization()).isNull();
    }

    @Test
    void getAuthorization_jeapTokenButNotAuthenticated_returnsNull() {
        JeapAuthenticationToken token = mock(JeapAuthenticationToken.class);
        when(token.isAuthenticated()).thenReturn(false);
        setAuthentication(token);

        assertThat(sut.getAuthorization()).isNull();
    }

    @Test
    void getAuthorization_authenticatedJeapToken_returnsAuthorizationFromToken() {
        JeapAuthenticationToken token = mock(JeapAuthenticationToken.class);
        when(token.isAuthenticated()).thenReturn(true);
        when(token.getUserRoles()).thenReturn(Set.of("jme_read"));
        when(token.getBusinessPartnerRoles()).thenReturn(Map.of("BP1", Set.of("jme_read")));
        setAuthentication(token);

        Authorization result = sut.getAuthorization();

        assertThat(result).isNotNull();
        assertThat(result.userroles()).containsExactly("jme_read");
        assertThat(result.bproles()).containsOnlyKeys("BP1");
        assertThat(result.bproles().get("BP1")).containsExactly("jme_read");
    }

    @Test
    void getAuthorization_authenticatedJeapTokenWithSemanticRoleStrings_passesThemThrough() {
        // Provider must not transform role strings — semantic-mode roles flow through verbatim.
        JeapAuthenticationToken token = mock(JeapAuthenticationToken.class);
        when(token.isAuthenticated()).thenReturn(true);
        when(token.getUserRoles()).thenReturn(Set.of("jme_@searchitem_#read"));
        when(token.getBusinessPartnerRoles()).thenReturn(Map.of("BP2", Set.of("jme_@searchitem_#read")));
        setAuthentication(token);

        Authorization result = sut.getAuthorization();

        assertThat(result).isNotNull();
        assertThat(result.userroles()).containsExactly("jme_@searchitem_#read");
        assertThat(result.bproles().get("BP2")).containsExactly("jme_@searchitem_#read");
    }

    private static void setAuthentication(org.springframework.security.core.Authentication authentication) {
        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
