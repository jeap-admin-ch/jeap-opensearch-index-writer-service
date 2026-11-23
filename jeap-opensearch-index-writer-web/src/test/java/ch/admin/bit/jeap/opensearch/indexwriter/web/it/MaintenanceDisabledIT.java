package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobHousekeeping;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobReportService;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobSubmissionService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "jeap.opensearch.indexwriter.maintenance.job-index=disabled_it_jobs",
        "jeap.opensearch.indexwriter.maintenance.task-index=disabled_it_tasks"})
class MaintenanceDisabledIT extends KafkaIntegrationTestBase {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("docker-hub.nexus.bit.admin.ch/opensearchproject/opensearch:3.3.2")
                    .asCompatibleSubstituteFor("opensearchproject/opensearch"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200));

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.opensearch.indexwriter.connection.url",
                () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        registry.add("spring.security.oauth2.client.provider.search-item-provider.token-uri",
                () -> wireMock.baseUrl() + "/oauth/token");
        registry.add("test.wiremock.base-url", wireMock::baseUrl);
    }

    private static final Set<String> MAINTENANCE_PACKAGES = Set.of(
            "ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job",
            "ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance",
            "ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance");

    @Autowired
    private ApplicationContext context;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void noMaintenanceBeansEndpointsOrIndicesWhenDisabled() throws Exception {
        assertThat(context.getBeanNamesForType(JobSubmissionService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JobReportService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JobHousekeeping.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MaintenanceProperties.class)).as("maintenance properties are not even bound").isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()).map(context::getType).filter(Objects::nonNull).map(Class::getPackageName))
                .as("beans of the maintenance job packages")
                .noneMatch(MAINTENANCE_PACKAGES::contains);
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.toString().contains("/api/upsert-jobs") || info.toString().contains("/api/delete-jobs"));

        HttpClient http = HttpClient.newHttpClient();
        for (String index : new String[]{"disabled_it_jobs", "disabled_it_tasks"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                    "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200) + "/" + index)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(index).isEqualTo(404);
        }
    }
}
