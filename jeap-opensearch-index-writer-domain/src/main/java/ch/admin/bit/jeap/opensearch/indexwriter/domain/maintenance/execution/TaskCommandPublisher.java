package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTask;

import java.util.List;

public interface TaskCommandPublisher {

    // Returns once the broker confirmed every command, throws TaskDispatchException otherwise
    void publish(Job job, List<JobTask> tasks);
}
