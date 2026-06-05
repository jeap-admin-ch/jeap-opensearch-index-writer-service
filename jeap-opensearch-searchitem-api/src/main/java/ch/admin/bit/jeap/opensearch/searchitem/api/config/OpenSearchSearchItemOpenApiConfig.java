package ch.admin.bit.jeap.opensearch.searchitem.api.config;

import ch.admin.bit.jeap.opensearch.searchitem.api.SearchItemsController;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info = @Info(
                title = "OpenSearch SearchItems API",
                description = "Retrieve search items to be indexed by the index writer service",
                contact = @Contact(
                        email = "jEAP-Community@bit.admin.ch",
                        name = "jEAP",
                        url = "https://confluence.eap.bit.admin.ch/display/BLUE/"
                )
        )
)
@Configuration
public class OpenSearchSearchItemOpenApiConfig {

    @Bean
    GroupedOpenApi openSearchSearchItemGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("OpenSearch SearchItems API")
                .pathsToMatch("/index-api/**")
                .packagesToScan(SearchItemsController.class.getPackageName())
                .build();
    }
}
