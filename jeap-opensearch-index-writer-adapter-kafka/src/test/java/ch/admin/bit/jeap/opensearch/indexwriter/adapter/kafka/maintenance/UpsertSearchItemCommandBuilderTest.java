package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpsertSearchItemCommandBuilderTest {

    private final Job job = MaintenanceTestJobs.job(JobType.UPSERT);
    private final JobTask task = MaintenanceTestJobs.task(job, 0, "12345", "17");

    @Test
    void build_carriesTheJobAndTaskInTheJeapEnvelope() {
        UpsertSearchItemCommand command = build(task);

        assertThat(command.getType().getName()).isEqualTo("UpsertSearchItemCommand");
        assertThat(command.getType().getVersion()).isEqualTo(UpsertSearchItemCommand.MESSAGE_TYPE_VERSION$);
        assertThat(command.getPublisher().getSystem()).isEqualTo("JEAP");
        assertThat(command.getPublisher().getService()).isEqualTo("my-index-writer");
        assertThat(command.getProcessId()).isEqualTo(job.jobId().toString());
        assertThat(command.getReferences().getSearchItem().getReferenceId()).isEqualTo("12345");
        assertThat(command.getReferences().getSearchItem().getReferenceVersion()).isEqualTo("17");
        assertThat(command.getPayload().getJobId()).isEqualTo(job.jobId().toString());
        assertThat(command.getPayload().getTaskId()).isEqualTo(task.taskId().toString());
        assertThat(command.getPayload().getMessageName()).isEqualTo("JmeDecreeDocumentCreatedEvent");
        assertThat(command.getPayload().getTopicName()).isEqualTo("jme-decree-document-created");
    }

    @Test
    void build_keepsTheIdempotenceIdentityWhenATaskIsSentAgain() {
        UpsertSearchItemCommand first = build(task);
        UpsertSearchItemCommand again = build(task);

        assertThat(first.getIdentity().getIdempotenceId()).isEqualTo(task.taskId().toString());
        assertThat(again.getIdentity().getIdempotenceId()).isEqualTo(first.getIdentity().getIdempotenceId());
        assertThat(again.getIdentity().getId()).as("each sent message has its own id").isNotEqualTo(first.getIdentity().getId());
    }

    @Test
    void build_omitsTheVersionOfAReferenceWithoutOne() {
        UpsertSearchItemCommand command = build(MaintenanceTestJobs.task(job, 1, "67890", null));

        assertThat(command.getReferences().getSearchItem().getReferenceVersion()).isNull();
    }

    private UpsertSearchItemCommand build(JobTask jobTask) {
        return UpsertSearchItemCommandBuilder.builder("JEAP", "my-index-writer").task(job, jobTask).build();
    }
}
