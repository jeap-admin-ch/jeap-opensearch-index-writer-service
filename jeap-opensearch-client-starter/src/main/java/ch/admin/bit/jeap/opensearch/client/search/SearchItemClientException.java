package ch.admin.bit.jeap.opensearch.client.search;

/**
 * Wrapper exception for OpenSearch/IO/deserialization errors so that the
 * {@link SearchItemClient} API does not have to declare checked exceptions.
 */
public class SearchItemClientException extends RuntimeException {

    public SearchItemClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public SearchItemClientException(String message) {
        super(message);
    }
}
