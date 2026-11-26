package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.MaintenanceTargetWriter;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs upsert jobs end to end: REST submission, task commands over Kafka, provider calls and writes to OpenSearch
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "jeap.opensearch.indexwriter.maintenance.enabled=true",
        "jeap.opensearch.indexwriter.maintenance.job-index=it_exec_jobs",
        "jeap.opensearch.indexwriter.maintenance.task-index=it_exec_tasks",
        "jeap.opensearch.indexwriter.maintenance.housekeeping-interval=PT1S",
        "jeap.opensearch.indexwriter.maintenance.task-max-attempts=3",
        "jeap.opensearch.indexwriter.maintenance.task-retry-initial-backoff=PT1S",
        "jeap.opensearch.indexwriter.maintenance.task-retry-max-backoff=PT2S",
        "jeap.swagger.status=open",
        "jeap.security.oauth2.resourceserver.system-name=it",
        "jeap.security.oauth2.resourceserver.authorization-server.issuer=http://localhost:1/auth",
        "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri=http://localhost:1/jwks"})
@DirtiesContext
@EmbeddedKafka(controlledShutdown = true, partitions = 1, topics = OpenSearchIndexWriterITApplication.UPSERT_COMMAND_TOPIC)
class MaintenanceUpsertExecutionIT extends KafkaIntegrationTestBase {

    private static final String YAML = "application/yaml";
    private static final String PROVIDER_PATH = "/index-api/searchitems";
    private static final String WRITE_ALIAS = "test_document_v1_write";
    private static final String FEATURE_FLAG_WRITE_ALIAS = "test_document_feature_flag_v1_write";
    private static final NamedFeature FEATURE_FLAG = new NamedFeature("IT_FEATURE_FLAG_INDEXING");
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
        registry.add("jeap.opensearch.indexwriter.connection.url", MaintenanceUpsertExecutionIT::openSearchUrl);
        registry.add("spring.security.oauth2.client.provider.search-item-provider.token-uri",
                () -> wireMock.baseUrl() + "/oauth/token");
        registry.add("test.wiremock.base-url", wireMock::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FeatureManager featureManager;
    @MockitoSpyBean
    private MaintenanceTargetWriter targetWriter;

    @BeforeEach
    void enableFeatureFlag() {
        featureManager.enable(FEATURE_FLAG);
    }

    @Test
    void upsert_restoresMissingAndReplacesOutdatedDocumentsWithCurrentData() throws Exception {
        openSearch("PUT", "/" + WRITE_ALIAS + "/_doc/exec-outdated?refresh=true", """
                {"origin":{"id":"exec-outdated"},"data":{"camel_case_field":"old","obsolete_field":"gone"}}""");
        stubCurrentItem("TestDocument", "exec-outdated", """
                {"camel_case_field":"new","another_camel_field":"current"}""");
        stubCurrentItem("TestDocument", "exec-missing", """
                {"camel_case_field":"restored","another_camel_field":"current"}""");
        UUID jobId = UUID.randomUUID();

        submitUpsert(jobId, "TestDocument", "- id: 'exec-outdated'\n  version: '17'\n- id: 'exec-missing'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("succeeded");
        assertThat(report.path("summary").path("succeeded").asInt()).isEqualTo(2);
        JsonNode outdated = entry(report, "exec-outdated");
        assertThat(outdated.path("state").asString()).isEqualTo("succeeded");
        assertThat(outdated.path("version").asString()).as("the reference version is echoed").isEqualTo("17");
        assertThat(outdated.path("actual-index-major-version").asInt()).isEqualTo(1);
        assertThat(outdated.path("actual-index-minor-version").asInt()).isZero();

        JsonNode replaced = documentSource(WRITE_ALIAS, "exec-outdated");
        assertThat(replaced.path("data").path("camel_case_field").asString()).isEqualTo("new");
        assertThat(replaced.path("data").has("obsolete_field")).as("removed fields are gone").isFalse();
        assertThat(replaced.path("search_item").path("major_version").asInt()).isEqualTo(1);
        assertThat(documentSource(WRITE_ALIAS, "exec-missing").path("data").path("camel_case_field").asString()).isEqualTo("restored");
        wireMock.verify(getRequestedFor(urlPathEqualTo(PROVIDER_PATH))
                .withQueryParam("origin_id", equalTo("exec-outdated"))
                .withQueryParam("index_major_version", equalTo("1"))
                .withQueryParam("origin_version", absent()));
    }

    @Test
    void upsert_missingSourceFailsOnlyThatTask() throws Exception {
        wireMock.stubFor(providerRequest("TestDocument", "exec-unknown").willReturn(aResponse().withStatus(404)));
        stubCurrentItem("TestDocument", "exec-known", """
                {"camel_case_field":"known","another_camel_field":"x"}""");
        UUID jobId = UUID.randomUUID();

        submitUpsert(jobId, "TestDocument", "- id: 'exec-unknown'\n- id: 'exec-known'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("partially-succeeded");
        JsonNode unknown = entry(report, "exec-unknown");
        assertThat(unknown.path("state").asString()).isEqualTo("failed");
        assertThat(unknown.path("error").path("code").asString()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(entry(report, "exec-known").path("state").asString()).isEqualTo("succeeded");
        assertThat(documentExists(WRITE_ALIAS, "exec-unknown")).isFalse();
    }

    @Test
    void upsert_switchedOffIndexingFlag_endsTasksAsIndexingDisabledForGood() throws Exception {
        stubCurrentItem("TestDocumentFeatureFlag", "exec-flagged", """
                {"camel_case_field":"x","another_camel_field":"y"}""");
        featureManager.disable(FEATURE_FLAG);
        UUID jobId = UUID.randomUUID();

        submitUpsert(jobId, "TestDocumentFeatureFlag", "- id: 'exec-flagged'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).as("disabled tasks count as not succeeded").isEqualTo("failed");
        assertThat(report.path("summary").path("indexing-disabled").asInt()).isEqualTo(1);
        assertThat(entry(report, "exec-flagged").path("state").asString()).isEqualTo("indexing-disabled");

        featureManager.enable(FEATURE_FLAG);
        Thread.sleep(3000);
        assertThat(entry(report(jobId), "exec-flagged").path("state").asString()).as("not rerun").isEqualTo("indexing-disabled");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PROVIDER_PATH)).withQueryParam("origin_id", equalTo("exec-flagged")));
        assertThat(documentExists(FEATURE_FLAG_WRITE_ALIAS, "exec-flagged")).isFalse();
    }

    @Test
    void upsert_transientProviderError_isRetriedUntilTheProviderIsBack() throws Exception {
        wireMock.stubFor(providerRequest("TestDocument", "exec-flaky").atPriority(5).willReturn(aResponse().withStatus(503)));
        UUID jobId = UUID.randomUUID();

        submitUpsert(jobId, "TestDocument", "- id: 'exec-flaky'");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            JsonNode flaky = entry(report(jobId), "exec-flaky");
            assertThat(flaky.path("state").asString()).isEqualTo("open");
            assertThat(flaky.path("attempts").asInt()).isPositive();
            assertThat(flaky.path("error").path("code").asString()).isEqualTo("SOURCE_UNAVAILABLE");
        });
        stubCurrentItem("TestDocument", "exec-flaky", """
                {"camel_case_field":"recovered","another_camel_field":"x"}""");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("succeeded");
        assertThat(entry(report, "exec-flaky").has("error")).isFalse();
        assertThat(documentSource(WRITE_ALIAS, "exec-flaky").path("data").path("camel_case_field").asString()).isEqualTo("recovered");
    }

    @Test
    void upsert_persistentProviderError_failsAfterTheMaximumNumberOfAttempts() throws Exception {
        // 500 rather than 503: the HTTP client itself retries a 503 once, which would blur the count of attempts
        wireMock.stubFor(providerRequest("TestDocument", "exec-down").willReturn(aResponse().withStatus(500)));
        UUID jobId = UUID.randomUUID();

        submitUpsert(jobId, "TestDocument", "- id: 'exec-down'");

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("failed");
        JsonNode down = entry(report, "exec-down");
        assertThat(down.path("state").asString()).isEqualTo("failed");
        assertThat(down.path("error").path("code").asString()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(down.path("error").path("message").asString()).startsWith("Gave up after 3 attempts");
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(PROVIDER_PATH)).withQueryParam("origin_id", equalTo("exec-down")));
    }

    @Test
    void upsert_redeliversUntilTheTaskIndexAcceptsTheResult() throws Exception {
        String originId = "exec-task-storage-blocked";
        stubCurrentItem("TestDocument", originId, """
                {"camel_case_field":"persisted","another_camel_field":"x"}""");
        AtomicBoolean blocked = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (blocked.compareAndSet(false, true)) {
                assertThat(openSearch("PUT", "/it_exec_tasks/_settings", """
                        {"index.blocks.write":true}""").path("acknowledged").asBoolean()).isTrue();
            }
            return null;
        }).when(targetWriter).upsert(anyString(), eq(originId), any());
        UUID jobId = UUID.randomUUID();

        try {
            submitUpsert(jobId, "TestDocument", "- id: '" + originId + "'");

            await().atMost(TIMEOUT).untilAsserted(() -> {
                assertThat(documentExists(WRITE_ALIAS, originId)).isTrue();
                assertThat(entry(report(jobId), originId).path("state").asString()).isEqualTo("open");
                assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(PROVIDER_PATH))
                        .withQueryParam("origin_id", equalTo(originId)))).hasSizeGreaterThanOrEqualTo(2);
            });
        } finally {
            assertThat(openSearch("PUT", "/it_exec_tasks/_settings", """
                    {"index.blocks.write":false}""").path("acknowledged").asBoolean()).isTrue();
        }

        JsonNode report = awaitCompleted(jobId);
        assertThat(report.path("job-result").asString()).isEqualTo("succeeded");
        JsonNode task = entry(report, originId);
        assertThat(task.path("state").asString()).isEqualTo("succeeded");
        assertThat(task.path("attempts").asInt()).isZero();
        assertThat(task.has("error")).isFalse();
    }

    private void submitUpsert(UUID jobId, String indexType, String references) throws Exception {
        String body = """
                message:
                  messageName: JmeDeclarationCreatedEvent
                  topicName: test-index-writer
                operation:
                  indexType: %s
                  indexOperation: UPSERT
                  indexMajorVersion: 1
                originReferences:
                %s
                """.formatted(indexType, references);
        mockMvc.perform(put("/api/upsert-jobs/" + jobId).contentType(YAML).content(body).with(authentication(token())))
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
        String yaml = mockMvc.perform(get("/api/upsert-jobs/" + jobId).accept(YAML).with(authentication(token())))
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

    private static ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken token() {
        return JeapAuthenticationTestTokenBuilder.create().withName("Example Operator").withExtId("123456")
                .withUserRoles(role("upsertjob", "create"), role("jobreport", "read")).build();
    }

    private static SemanticApplicationRole role(String resource, String operation) {
        return SemanticApplicationRole.builder().system("it").resource(resource).operation(operation).build();
    }

    private static MappingBuilder providerRequest(String indexType, String originId) {
        return com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(PROVIDER_PATH))
                .withQueryParam("index_type", equalTo(indexType))
                .withQueryParam("origin_id", equalTo(originId))
                .withQueryParam("index_major_version", equalTo("1"));
    }

    private static void stubCurrentItem(String indexType, String originId, String data) {
        wireMock.stubFor(providerRequest(indexType, originId).atPriority(1).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withHeader("index-type", indexType)
                .withHeader("index-major-version", "1")
                .withHeader("index-minor-version", "0")
                .withBody("{\"origin\":{\"id\":\"" + originId + "\"},\"data\":" + data + "}")));
    }

    private static boolean documentExists(String index, String id) throws Exception {
        return openSearch("GET", "/" + index + "/_doc/" + id, null).path("found").asBoolean(false);
    }

    private static JsonNode documentSource(String index, String id) throws Exception {
        JsonNode document = openSearch("GET", "/" + index + "/_doc/" + id, null);
        assertThat(document.path("found").asBoolean(false)).as("document %s exists", id).isTrue();
        return document.path("_source");
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
