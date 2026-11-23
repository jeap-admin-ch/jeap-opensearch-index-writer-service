package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JobStorage {

    private JobStorage() {
    }

    public interface JobRepository {

        // false if a job with this id already exists
        boolean create(Job job);

        // withReferences=false omits the potentially large reference list
        Optional<Job> find(UUID jobId, boolean withReferences);

        // Conditional on the job version: false if the job changed in the meantime
        boolean markMaterialized(Job job);

        // The find methods page in a stable order (start time, then job id):
        // after is the last job of the previous page, null for the first page
        List<Job> findMaterializingStartedBefore(Instant startedBefore, Job after, int limit);

        List<Job> findOpenMaterialized(Job after, int limit);

        // Conditional on the job version: completes the job as failed with materialization state FAILED
        boolean failMaterialization(Job job, Instant completedAt);

        // Conditional on the job version: false if the job changed in the meantime
        boolean complete(Job job, JobResult result, Instant completedAt);

        List<Job> findCompletedBefore(Instant completedBefore, Job after, int limit);

        void delete(UUID jobId);
    }

    public interface TaskRepository {

        // Existing tasks are left untouched
        void createMissing(List<JobTask> tasks);

        void refresh();

        List<JobTask> findByPositionRange(UUID jobId, int fromInclusive, int toExclusive);

        TaskCounts counts(UUID jobId);

        // Throws if not every task of the job could be deleted, so that the job itself is kept and retried
        void deleteByJobId(UUID jobId);
    }

    public interface JobTargetResolver {

        ResolvedTarget resolveWriteTarget(String writeAlias);
    }
}
