package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

public enum MaterializationState {
    // FAILED: the storage permanently rejected the tasks; the job is completed as failed and never retried
    MATERIALIZING, MATERIALIZED, FAILED
}
