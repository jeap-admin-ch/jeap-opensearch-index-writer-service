package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import lombok.Getter;

@Getter
public class TargetWriteException extends IndexWriterException {

    private final TaskErrorCode code;

    private TargetWriteException(TaskErrorCode code, String message, boolean retryable, Throwable cause) {
        super(message, retryable, cause);
        this.code = code;
    }

    public static TargetWriteException permanent(TaskErrorCode code, String message, Throwable cause) {
        return new TargetWriteException(code, message, false, cause);
    }

    public static TargetWriteException unavailable(String message, Throwable cause) {
        return new TargetWriteException(TaskErrorCode.TARGET_UNAVAILABLE, message, true, cause);
    }
}
