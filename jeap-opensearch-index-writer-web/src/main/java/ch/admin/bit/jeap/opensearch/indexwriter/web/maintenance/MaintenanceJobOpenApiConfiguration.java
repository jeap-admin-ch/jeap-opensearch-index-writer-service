package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Only the job paths: matching /api/** would pull the consuming service's own APIs into this group. The service keeps
// its single @OpenAPIDefinition (title, contact); the OIDC scheme comes from the jEAP swagger starter.
@Configuration(proxyBeanMethods = false)
@ConditionalOnMaintenanceEnabled
class MaintenanceJobOpenApiConfiguration {

    static final String GROUP = "maintenance-jobs";

    @Bean
    GroupedOpenApi maintenanceJobsOpenApi() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .pathsToMatch("/api/upsert-jobs/**", "/api/delete-jobs/**")
                .build();
    }
}
