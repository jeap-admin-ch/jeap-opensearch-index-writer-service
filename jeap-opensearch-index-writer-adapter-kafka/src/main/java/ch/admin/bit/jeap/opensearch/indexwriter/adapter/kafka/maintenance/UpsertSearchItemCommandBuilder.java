package ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.maintenance;

import ch.admin.bit.jeap.command.avro.AvroCommandBuilder;
import ch.admin.bit.jeap.messaging.avro.AvroMessageType;
import ch.admin.bit.jeap.opensearch.command.upsert.SearchItemReference;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommand;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommandPayload;
import ch.admin.bit.jeap.opensearch.command.upsert.UpsertSearchItemCommandReferences;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;

class UpsertSearchItemCommandBuilder extends AvroCommandBuilder<UpsertSearchItemCommandBuilder, UpsertSearchItemCommand> {

    private final String systemName;
    private final String serviceName;
    private Job job;
    private JobTask task;

    private UpsertSearchItemCommandBuilder(String systemName, String serviceName) {
        super(UpsertSearchItemCommand::new);
        this.systemName = systemName;
        this.serviceName = serviceName;
    }

    static UpsertSearchItemCommandBuilder builder(String systemName, String serviceName) {
        return new UpsertSearchItemCommandBuilder(systemName, serviceName);
    }

    static AvroMessageType messageType() {
        AvroMessageType type = new AvroMessageType();
        type.setName(UpsertSearchItemCommand.getClassSchema().getName());
        type.setVersion(UpsertSearchItemCommand.MESSAGE_TYPE_VERSION$);
        return type;
    }

    UpsertSearchItemCommandBuilder task(Job job, JobTask task) {
        this.job = job;
        this.task = task;
        return this;
    }

    // The idempotence id is the task id, so every command sent again for the same task carries the same identity
    @Override
    public UpsertSearchItemCommand build() {
        idempotenceId = task.taskId().toString();
        setReferences(UpsertSearchItemCommandReferences.newBuilder()
                .setSearchItem(SearchItemReference.newBuilder()
                        .setReferenceId(task.originId())
                        .setReferenceVersion(task.referenceVersion())
                        .build())
                .build());
        setPayload(UpsertSearchItemCommandPayload.newBuilder()
                .setJobId(job.jobId().toString())
                .setTaskId(task.taskId().toString())
                .setMessageName(job.request().messageName())
                .setTopicName(job.request().topicName())
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
    protected UpsertSearchItemCommandBuilder self() {
        return this;
    }
}
