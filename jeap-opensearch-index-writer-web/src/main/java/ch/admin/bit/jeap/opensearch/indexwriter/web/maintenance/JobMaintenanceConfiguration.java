package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// The properties are bound (and validated) only when maintenance is enabled
@Configuration(proxyBeanMethods = false)
@ConditionalOnMaintenanceEnabled
@EnableConfigurationProperties(MaintenanceProperties.class)
class JobMaintenanceConfiguration {

    @Bean
    JobClock jobClock() {
        return new JobClock(Clock.systemUTC());
    }
}
