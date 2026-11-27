package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommand;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteSearchItemCommandBuilderTest {

    private final Job job = MaintenanceTestJobs.job(JobType.DELETE);
    private final JobTask task = MaintenanceTestJobs.task(job, 0, "12345", null);

    @Test
    void build_carriesOnlyTheIdAndTheTaskInTheJeapEnvelope() {
        DeleteSearchItemCommand command = build();

        assertThat(command.getType().getName()).isEqualTo("DeleteSearchItemCommand");
        assertThat(command.getType().getVersion()).isEqualTo(DeleteSearchItemCommand.MESSAGE_TYPE_VERSION$);
        assertThat(command.getPublisher().getSystem()).isEqualTo("JEAP");
        assertThat(command.getProcessId()).isEqualTo(job.jobId().toString());
        assertThat(command.getReferences().getSearchItem().getReferenceId()).isEqualTo("12345");
        assertThat(command.getPayload().getJobId()).isEqualTo(job.jobId().toString());
        assertThat(command.getPayload().getTaskId()).isEqualTo(task.taskId().toString());
    }

    @Test
    void build_keepsTheIdempotenceIdentityWhenATaskIsSentAgain() {
        assertThat(build().getIdentity().getIdempotenceId()).isEqualTo(task.taskId().toString())
                .isEqualTo(build().getIdentity().getIdempotenceId());
    }

    private DeleteSearchItemCommand build() {
        return DeleteSearchItemCommandBuilder.builder("JEAP", "my-index-writer").task(job, task).build();
    }
}
