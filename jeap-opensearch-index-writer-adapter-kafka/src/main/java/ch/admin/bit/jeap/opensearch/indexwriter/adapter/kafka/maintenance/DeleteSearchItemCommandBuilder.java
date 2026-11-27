package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.command.avro.AvroCommandBuilder;
import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommand;
import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommandPayload;
import ch.admin.bit.jeap.opensearch.command.delete.DeleteSearchItemCommandReferences;
import ch.admin.bit.jeap.opensearch.command.delete.SearchItemReference;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;

// The command names the task only; the targets to delete from are taken from the stored job
class DeleteSearchItemCommandBuilder extends AvroCommandBuilder<DeleteSearchItemCommandBuilder, DeleteSearchItemCommand> {

    private final String systemName;
    private final String serviceName;
    private Job job;
    private JobTask task;

    private DeleteSearchItemCommandBuilder(String systemName, String serviceName) {
        super(DeleteSearchItemCommand::new);
        this.systemName = systemName;
        this.serviceName = serviceName;
    }

    static DeleteSearchItemCommandBuilder builder(String systemName, String serviceName) {
        return new DeleteSearchItemCommandBuilder(systemName, serviceName);
    }

    static AvroMessageType messageType() {
        AvroMessageType type = new AvroMessageType();
        type.setName(DeleteSearchItemCommand.getClassSchema().getName());
        type.setVersion(DeleteSearchItemCommand.MESSAGE_TYPE_VERSION$);
        return type;
    }

    DeleteSearchItemCommandBuilder task(Job job, JobTask task) {
        this.job = job;
        this.task = task;
        return this;
    }

    // The idempotence id is the task id, so every command sent again for the same task carries the same identity
    @Override
    public DeleteSearchItemCommand build() {
        idempotenceId = task.taskId().toString();
        setReferences(DeleteSearchItemCommandReferences.newBuilder()
                .setSearchItem(SearchItemReference.newBuilder().setReferenceId(task.originId()).build())
                .build());
        setPayload(DeleteSearchItemCommandPayload.newBuilder()
                .setJobId(job.jobId().toString())
                .setTaskId(task.taskId().toString())
                .build());
        setProcessId(job.jobId().toString());
        return super.build();
    }

    @Override
    protected String getServiceName() {
        return serviceName;
    }

    @Override
    protected String getSystemName() {
        return systemName;
    }

    @Override
    protected DeleteSearchItemCommandBuilder self() {
        return this;
    }
}
