package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

public class JobTargetNameInvalidException extends IndexWriterException {

    public static final String ERROR_CODE = "target-name-invalid";

    private final String targetIndex;

    public JobTargetNameInvalidException(String targetIndex) {
        super("Not a concrete index name: '%s'".formatted(targetIndex), false);
        this.targetIndex = targetIndex;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getTargetIndex() {
        return targetIndex;
    }
}
