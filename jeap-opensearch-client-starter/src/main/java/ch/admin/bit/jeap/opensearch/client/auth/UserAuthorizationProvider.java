package ch.admin.bit.jeap.opensearch.client.auth;

public interface UserAuthorizationProvider {

    /**
     * @return Authorization of the currently authenticated principal, or {@code null}
     *         if no usable security context is available.
     */
    Authorization getAuthorization();
}
