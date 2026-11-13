package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

public class JobTargetNotAllowedException extends IndexWriterException {

    public static final String ERROR_CODE = "not-allowed";

    private final transient JobTargetAcceptance acceptance;

    JobTargetNotAllowedException(JobTargetAcceptance acceptance) {
        super(acceptance.reason(), false);
        this.acceptance = acceptance;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getTargetIndex() {
        return acceptance.targetIndex();
    }

    public String getReason() {
        return acceptance.reason();
    }
}
