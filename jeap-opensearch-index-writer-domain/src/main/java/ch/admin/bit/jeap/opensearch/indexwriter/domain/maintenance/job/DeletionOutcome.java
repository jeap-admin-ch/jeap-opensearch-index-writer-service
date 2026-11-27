package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

public enum DeletionOutcome {
    DELETED, ALREADY_ABSENT, FAILED;

    public boolean isSuccess() {
        return this != FAILED;
    }
}
