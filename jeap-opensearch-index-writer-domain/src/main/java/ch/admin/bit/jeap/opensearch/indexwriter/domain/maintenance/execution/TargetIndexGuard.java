package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// OpenSearch creates a missing index on write, so an upsert into a deleted target would silently create a new empty
// index without the read alias. The target is checked before writing; a successful check is reused for a minute per job.
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
public class TargetIndexGuard {

    static final Duration VERIFIED_FOR = Duration.ofMinutes(1);
    private static final int MAX_ENTRIES = 1000;

    private final MaintenanceTargetWriter targetWriter;
    private final JobClock clock;
    private final Map<UUID, Instant> verifiedUntil = new ConcurrentHashMap<>();

    public void requireTarget(Job job) {
        Instant now = clock.now();
        Instant until = verifiedUntil.get(job.jobId());
        if (until != null && until.isAfter(now)) {
            return;
        }
        JobTarget target = job.target();
        Optional<String> uuid = targetWriter.findIndexUuid(target.physicalIndex());
        if (uuid.isEmpty()) {
            throw TargetWriteException.permanent(TaskErrorCode.TARGET_INDEX_MISSING,
                    "The target index %s no longer exists; the document was not written".formatted(target.physicalIndex()), null);
        }
        // Jobs accepted without a known uuid only get the existence check
        if (target.physicalIndexUuid() != null && !target.physicalIndexUuid().equals(uuid.get())) {
            throw TargetWriteException.permanent(TaskErrorCode.TARGET_INDEX_MISSING,
                    "The target index %s was recreated after the job was accepted (uuid %s, accepted %s); the document was not written"
                            .formatted(target.physicalIndex(), uuid.get(), target.physicalIndexUuid()), null);
        }
        if (verifiedUntil.size() >= MAX_ENTRIES) {
            verifiedUntil.values().removeIf(expiry -> !expiry.isAfter(now));
        }
        verifiedUntil.put(job.jobId(), now.plus(VERIFIED_FOR));
    }
}
