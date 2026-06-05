package ch.admin.bit.jeap.opensearch.searchitem.api.config;

import ch.admin.bit.jeap.opensearch.searchitem.api.SearchItemsController;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackageClasses = {SearchItemsController.class})
class OpenSearchSearchItemApiConfig {
}
