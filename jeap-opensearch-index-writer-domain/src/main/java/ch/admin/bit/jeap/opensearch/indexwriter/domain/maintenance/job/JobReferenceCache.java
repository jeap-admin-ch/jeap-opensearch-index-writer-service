package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobReference;
import org.springframework.stereotype.Component;

import java.util.*;

// The reference list of a job (up to max-request-bytes) never changes after submission. Reports need it while tasks
// are still being created, so the few most recent jobs are kept instead of reading the list per report page. The
// request hash is part of the key, as a job id can be reused once retention deleted the job.
@Component
@ConditionalOnMaintenanceEnabled
public class JobReferenceCache {

    private static final int SIZE = 2;

    private record Key(UUID jobId, String requestHash) {
    }

    private final Map<Key, List<JobReference>> cache = Collections.synchronizedMap(new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, List<JobReference>> eldest) {
            return size() > SIZE;
        }
    });

    Optional<List<JobReference>> get(Job job) {
        return Optional.ofNullable(cache.get(new Key(job.jobId(), job.requestHash())));
    }

    void put(Job job, List<JobReference> references) {
        cache.put(new Key(job.jobId(), job.requestHash()), references);
    }
}
