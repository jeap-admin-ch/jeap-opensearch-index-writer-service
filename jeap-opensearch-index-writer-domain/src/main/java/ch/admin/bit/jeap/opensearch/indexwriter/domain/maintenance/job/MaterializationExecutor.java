package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

// Deliberately not a java.util.concurrent.Executor: an Executor bean would replace Spring Boot's default task executor
// of the consuming service
public interface MaterializationExecutor {

    // false if the task was not accepted; the housekeeping then resumes the job later
    boolean submit(Runnable task);
}
