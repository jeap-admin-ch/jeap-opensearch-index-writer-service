package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs delete jobs end to end against the two majors of TestDocumentDelete: REST submission, task commands over Kafka
// and deletes in OpenSearch, without any provider call
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "jeap.opensearch.indexwriter.maintenance.enabled=true",
        "jeap.opensearch.indexwriter.maintenance.job-index=it_del_jobs",
        "jeap.opensearch.indexwriter.maintenance.task-index=it_del_tasks",
        "jeap.opensearch.indexwriter.maintenance.housekeeping-interval=PT1S",
        "jeap.swagger.status=open",
        "jeap.security.oauth2.resourceserver.system-name=it",
        "jeap.security.oauth2.resourceserver.authorization-server.issuer=http://localhost:1/auth",
        "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri=http://localhost:1/jwks"})
@DirtiesContext
@EmbeddedKafka(controlledShutdown = true, partitions = 1, topics = {OpenSearchIndexWriterITApplication.UPSERT_COMMAND_TOPIC,
        OpenSearchIndexWriterITApplication.DELETE_COMMAND_TOPIC})
class MaintenanceDeleteExecutionIT extends KafkaIntegrationTestBase {

    private static final String YAML = "application/yaml";
    private static final String V1_ALIAS = "test_document_delete_v1_write";
    private static final String V2_ALIAS = "test_document_delete_v2_write";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder().build();

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
        registry.add("jeap.opensearch.indexwriter.connection.url", MaintenanceDeleteExecutionIT::openSearchUrl);
        registry.add("spring.security.oauth2.client.provider.search-item-provider.token-uri",
                () -> wireMock.baseUrl() + "/oauth/token");
        registry.add("test.wiremock.base-url", wireMock::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void delete_explicitMajor_removesOnlyThatMajorsCopy() throws Exception {
        index(V1_ALIAS, "del-explicit");
        index(V2_ALIAS, "del-explicit");
        UUID jobId = UUID.randomUUID();

        submitDelete(jobId, "indexMajorVersion: 1\n", "- id: 'del-explicit'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("succeeded");
        assertThat(report.path("target-selection").asString()).isEqualTo("explicit");
        assertThat(report.path("targets")).hasSize(1);
        assertThat(targetOutcomes(entry(report, "del-explicit"))).containsExactly(tuple(1, "deleted"));
        assertThat(exists(V1_ALIAS, "del-explicit")).isFalse();
        assertThat(exists(V2_ALIAS, "del-explicit")).as("the other major keeps its copy").isTrue();
    }

    @Test
    void delete_omittedMajor_removesEveryCopyCountsAbsentAsSuccessAndCallsNoProvider() throws Exception {
        index(V1_ALIAS, "del-only-v1");
        index(V1_ALIAS, "del-both");
        index(V2_ALIAS, "del-both");
        index(V1_ALIAS, "del-unrelated");
        index(V2_ALIAS, "del-unrelated");
        UUID jobId = UUID.randomUUID();

        submitDelete(jobId, "", "- id: 'del-only-v1'\n- id: 'del-both'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("succeeded");
        assertThat(report.path("target-selection").asString()).isEqualTo("all");
        assertThat(report.path("targets")).hasSize(2);
        assertThat(targetOutcomes(entry(report, "del-only-v1"))).containsExactly(tuple(1, "deleted"), tuple(2, "already-absent"));
        assertThat(targetOutcomes(entry(report, "del-both"))).containsExactly(tuple(1, "deleted"), tuple(2, "deleted"));
        assertThat(exists(V1_ALIAS, "del-both") || exists(V2_ALIAS, "del-both")).isFalse();
        assertThat(exists(V1_ALIAS, "del-unrelated") && exists(V2_ALIAS, "del-unrelated")).as("unrelated ids stay").isTrue();
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void delete_blockedTarget_failsThatMajorAndKeepsTheOtherOutcome() throws Exception {
        index(V1_ALIAS, "del-blocked");
        index(V2_ALIAS, "del-blocked");
        String v2Index = physicalIndex(V2_ALIAS);
        openSearch("PUT", "/" + v2Index + "/_settings", "{\"index.blocks.write\":true}");
        try {
            UUID jobId = UUID.randomUUID();

            submitDelete(jobId, "", "- id: 'del-blocked'");

            JsonNode report = awaitCompleted(jobId);
            assertThat(report.path("job-result").asString()).isEqualTo("failed");
            JsonNode entry = entry(report, "del-blocked");
            assertThat(entry.path("state").asString()).isEqualTo("failed");
            assertThat(targetOutcomes(entry)).containsExactly(tuple(1, "deleted"), tuple(2, "failed"));
            JsonNode error = entry.path("targets").get(1).path("error");
            assertThat(error.path("code").asString()).isEqualTo("INDEX_NOT_WRITABLE");
            assertThat(error.path("target-index").asString()).isEqualTo(v2Index);
            assertThat(exists(V2_ALIAS, "del-blocked")).as("a real failure, not already absent").isTrue();
        } finally {
            openSearch("PUT", "/" + v2Index + "/_settings", "{\"index.blocks.write\":null}");
        }
    }

    private void submitDelete(UUID jobId, String majorLine, String references) throws Exception {
        String body = """
                indexType: TestDocumentDelete
                %soriginReferences:
                %s
                """.formatted(majorLine, references);
        mockMvc.perform(put("/api/delete-jobs/" + jobId).contentType(YAML).content(body).with(authentication(token())))
                .andExpect(status().isOk());
    }

    private JsonNode awaitCompleted(UUID jobId) {
        List<JsonNode> completed = new ArrayList<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode report = report(jobId);
            assertThat(report.path("job-state").asString()).isEqualTo("completed");
            completed.add(report);
        });
        return completed.getLast();
    }

    private JsonNode report(UUID jobId) throws Exception {
        String yaml = mockMvc.perform(get("/api/delete-jobs/" + jobId).accept(YAML).with(authentication(token())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return YAML_MAPPER.readTree(yaml);
    }

    private static JsonNode entry(JsonNode report, String originId) {
        for (JsonNode reference : report.path("originReferences")) {
            if (originId.equals(reference.path("id").asString())) {
                return reference;
            }
        }
        throw new AssertionError("No report entry for " + originId + " in " + report);
    }

    private static List<Tuple> targetOutcomes(JsonNode entry) {
        List<Tuple> outcomes = new ArrayList<>();
        for (JsonNode target : entry.path("targets")) {
            outcomes.add(tuple(target.path("index-major-version").asInt(), target.path("deletion-outcome").asString()));
        }
        return outcomes;
    }

    private static JeapAuthenticationToken token() {
        return JeapAuthenticationTestTokenBuilder.create().withName("Example Operator").withExtId("123456")
                .withUserRoles(role("deletejob", "create"), role("jobreport", "read")).build();
    }

    private static SemanticApplicationRole role(String resource, String operation) {
        return SemanticApplicationRole.builder().system("it").resource(resource).operation(operation).build();
    }

    private static void index(String alias, String id) throws Exception {
        openSearch("PUT", "/" + alias + "/_doc/" + id + "?refresh=true", "{\"origin\":{\"id\":\"" + id + "\"},\"data\":{}}");
    }

    private static boolean exists(String alias, String id) throws Exception {
        return openSearch("GET", "/" + alias + "/_doc/" + id, null).path("found").asBoolean(false);
    }

    private static String physicalIndex(String alias) throws Exception {
        return openSearch("GET", "/_alias/" + alias, null).propertyNames().iterator().next();
    }

    private static JsonNode openSearch(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchUrl() + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return JSON.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }
    }

    private static String openSearchUrl() {
        return "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
    }
}
