package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

public class TaskDispatchException extends IndexWriterException {

    public TaskDispatchException(String message, Throwable cause) {
        super(message, true, cause);
    }
}
