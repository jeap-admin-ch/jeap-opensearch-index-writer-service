package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;

import java.util.Optional;

// Writes to the physical index stored with a job, never through an alias; failures are reported as TargetWriteException
public interface MaintenanceTargetWriter {

    // Empty if the index does not exist
    Optional<String> findIndexUuid(String physicalIndex);

    @SuppressWarnings("java:S1452")
    void upsert(String physicalIndex, String documentId, SearchItemIndexed<?> searchItem);
}
