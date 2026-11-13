package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

public class JobTargetNotFoundException extends IndexWriterException {

    public static final String ERROR_CODE = "target-not-found";

    private final String targetIndex;

    public JobTargetNotFoundException(String targetIndex) {
        super("Target index '%s' does not exist".formatted(targetIndex), false);
        this.targetIndex = targetIndex;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getTargetIndex() {
        return targetIndex;
    }
}
