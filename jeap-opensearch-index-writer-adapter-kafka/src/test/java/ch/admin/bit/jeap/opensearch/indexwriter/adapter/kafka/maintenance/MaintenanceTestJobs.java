package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.messaging.avro.security.AvroClassSecurity;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MajorSelection;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class MaintenanceTestJobs {

    static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    // Without a Spring context the jEAP Avro class whitelist is not installed, Avro would refuse the command classes
    static {
        AvroClassSecurity.installDefaultIfMissing();
    }

    private MaintenanceTestJobs() {
    }

    static Job job(JobType type) {
        JobRequest request = type == JobType.UPSERT
                ? new JobRequest(type, "JmeDecreeDocumentCreatedEvent", "jme-decree-document-created", "DecreeDocument", 1, List.of())
                : new JobRequest(type, null, null, "DecreeDocument", 1, List.of());
        return new Job(UUID.randomUUID(), "hash", JobState.OPEN, null, 2, MaterializationState.MATERIALIZED, NOW, null,
                new SubmittedBy("Operator", "123"), request,
                new JobTarget(MajorSelection.EXPLICIT, 1, "jme_decree_document_v1-000001", "uuid", JobTarget.ROLLOVER_NOT_DETECTED),
                Dispatch.NONE, 0, 1);
    }

    static JobTask task(Job job, int position, String originId, String version) {
        return JobTask.open(job.jobId(), position, originId, version, NOW);
    }
}
