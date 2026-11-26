package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;

import java.util.UUID;

// messageName and topicName are set for upsert commands only
public record TaskCommand(JobType type, UUID jobId, UUID taskId, String originId, String referenceVersion,
                          String messageName, String topicName) {
}
