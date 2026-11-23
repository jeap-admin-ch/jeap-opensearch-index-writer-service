package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnMaintenanceEnabled
@Slf4j
class ThreadPoolMaterializationExecutor implements MaterializationExecutor, DisposableBean {

    private static final int THREADS = 2;
    private static final int QUEUE_CAPACITY = 20;

    private final ThreadPoolExecutor executor;

    ThreadPoolMaterializationExecutor() {
        executor = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofPlatform().name("job-materialization-", 0).daemon().factory());
        executor.allowCoreThreadTimeOut(true);
    }

    // A rejected or interrupted task creation is not lost: the housekeeping resumes every job left materializing
    @Override
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException _) {
            log.warn("Task creation queue is full, the housekeeping creates the tasks later");
            return false;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
