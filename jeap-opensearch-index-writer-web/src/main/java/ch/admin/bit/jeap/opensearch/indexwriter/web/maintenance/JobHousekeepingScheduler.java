package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobHousekeeping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Its own thread instead of @EnableScheduling, which would activate every @Scheduled method of the consuming service
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
class JobHousekeepingScheduler implements SmartLifecycle {

    private final JobHousekeeping housekeeping;
    private final MaintenanceProperties properties;

    private ScheduledExecutorService scheduler;

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("job-housekeeping").daemon().factory());
        long interval = properties.getHousekeepingInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::runSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    // An exception escaping a scheduled run would silently cancel all further runs
    private void runSafely() {
        try {
            housekeeping.run();
        } catch (RuntimeException e) {
            log.warn("Job housekeeping run failed, it is retried with the next run", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
