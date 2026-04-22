package ch.admin.bit.jeap.opensearch.indexwriter.domain.exception;

import lombok.Getter;

@Getter
public abstract class IndexWriterException extends RuntimeException {

    private final boolean retryable;

    protected IndexWriterException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    protected IndexWriterException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }
}
