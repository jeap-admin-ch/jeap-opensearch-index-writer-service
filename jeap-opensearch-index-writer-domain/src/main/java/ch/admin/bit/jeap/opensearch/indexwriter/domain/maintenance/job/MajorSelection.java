package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

public enum MajorSelection {
    // ALL: a delete job without indexMajorVersion, one target per major configured when the job was accepted
    EXPLICIT, HIGHEST, ALL
}
