package ch.admin.bit.jeap.opensearch.registry.git;

public class GitClientException extends RuntimeException {
    public GitClientException(String message) {
        super(message);
    }

    public GitClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
