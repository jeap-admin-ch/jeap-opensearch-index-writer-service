package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

public enum TaskState {
    // INDEXING_DISABLED: the indexing flag of the job's operation was off when the task ran; final, never rerun
    OPEN, SUCCEEDED, FAILED, INDEXING_DISABLED;

    public boolean isFinal() {
        return this != OPEN;
    }
}
