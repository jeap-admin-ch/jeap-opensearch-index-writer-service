package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

public class JobTargetPolicyCheckFailedException extends IndexWriterException {

    public static final String ERROR_CODE = "policy-check-failed";

    private final String targetIndex;

    public JobTargetPolicyCheckFailedException(String targetIndex, String detail) {
        this(targetIndex, detail, true);
    }

    private JobTargetPolicyCheckFailedException(String targetIndex, String detail, boolean retryable) {
        super("Failed to read the ISM policy applied to target index '%s': %s".formatted(targetIndex, detail), retryable);
        this.targetIndex = targetIndex;
    }

    // Retrying cannot succeed until the credentials are fixed
    public static JobTargetPolicyCheckFailedException credentialsRejected(String targetIndex, String detail) {
        return new JobTargetPolicyCheckFailedException(targetIndex, detail + ", the credentials were rejected", false);
    }

    public JobTargetPolicyCheckFailedException(String targetIndex, Throwable cause) {
        super("Failed to read the ISM policy applied to target index '%s'".formatted(targetIndex), true, cause);
        this.targetIndex = targetIndex;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getTargetIndex() {
        return targetIndex;
    }
}
