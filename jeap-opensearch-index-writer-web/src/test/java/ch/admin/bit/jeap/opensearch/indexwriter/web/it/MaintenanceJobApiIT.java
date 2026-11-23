package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptanceCheck;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "jeap.opensearch.indexwriter.maintenance.enabled=true",
        "jeap.opensearch.indexwriter.maintenance.job-index=it_jobs",
        "jeap.opensearch.indexwriter.maintenance.task-index=it_tasks",
        "jeap.opensearch.indexwriter.maintenance.max-references=5",
        "jeap.opensearch.indexwriter.maintenance.max-request-bytes=1048576",
        "jeap.swagger.status=open",
        "jeap.security.oauth2.resourceserver.system-name=it",
        "jeap.security.oauth2.resourceserver.authorization-server.issuer=http://localhost:1/auth",
        "jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri=http://localhost:1/jwks"})
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MaintenanceJobApiIT extends KafkaIntegrationTestBase {

    private static final String YAML = "application/yaml";
    private static final String DELETE_WRITE_ALIAS = "test_document_delete_v1_write";
    private static final String ROLLOVER_POLICY = """
            {"policy":{"description":"it","default_state":"hot","states":[
              {"name":"hot","actions":[{"rollover":{"min_doc_count":1000000}}],"transitions":[]}]}}
            """;

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JobTargetAcceptanceCheck acceptanceCheck;

    private JeapAuthenticationToken authentication;

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(authentication(authentication)));
    }

    private static SemanticApplicationRole role(String resource, String operation) {
        return SemanticApplicationRole.builder().system("it").resource(resource).operation(operation).build();
    }

    private void authenticateWith(SemanticApplicationRole... roles) {
        authentication = JeapAuthenticationTestTokenBuilder.create()
                .withName("Example Operator").withExtId("123456").withUserRoles(roles).build();
    }

    private static String upsertYaml(String majorLine, String versionOfFirst) {
        return """
                message:
                  messageName: JmeDeclarationCreatedEvent
                  topicName: test-index-writer
                operation:
                  indexType: TestDocument
                  indexOperation: UPSERT
                %s
                originReferences:
                - id: 'doc-1'
                  version: '%s'
                - id: 'doc-2'
                """.formatted(majorLine, versionOfFirst);
    }

    private static final String DELETE_YAML = """
            indexType: TestDocumentDelete
            indexMajorVersion: 1
            originReferences:
            - id: 'doc-1'
            - id: 'doc-2'
            """;

    private MvcResult putUpsert(UUID id, String body, int expectedStatus) throws Exception {
        return perform(put("/api/upsert-jobs/" + id).contentType(YAML).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    @Test
    void upsert_explicitMajor_isStoredAndRetrievable() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();

        String report = putUpsert(id, upsertYaml("  indexMajorVersion: 1", "17"), 200).getResponse().getContentAsString();
        String retrieved = perform(get("/api/upsert-jobs/" + id).accept(YAML))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(YAML))
                .andReturn().getResponse().getContentAsString();

        assertThat(report).contains("job-state: open").contains("selection: explicit").contains("index-major-version: 1")
                .contains("started-by-name: Example Operator").contains("total-references: 2")
                .contains("version: \"17\"").contains("id: doc-1").contains("id: doc-2")
                .doesNotContain("actual-index-major-version");
        assertThat(retrieved).contains("job-id: " + id).contains("version: \"17\"");
    }

    @Test
    void upsert_omittedMajor_selectsHighestAndSameContentReturnsSameJob() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("deletejob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();

        String first = putUpsert(id, upsertYaml("", "17"), 200).getResponse().getContentAsString();
        String second = putUpsert(id, upsertYaml("", "17"), 200).getResponse().getContentAsString();

        assertThat(first).contains("selection: highest").contains("index-major-version: 1");
        assertThat(second).contains("selection: highest").contains("job-id: " + id);
    }

    @Test
    void upsert_sameIdChangedVersionOrSelector_isConflict() throws Exception {
        authenticateWith(role("upsertjob", "create"));
        UUID id = UUID.randomUUID();
        putUpsert(id, upsertYaml("  indexMajorVersion: 1", "17"), 200);

        putUpsert(id, upsertYaml("  indexMajorVersion: 1", "18"), 409);
        putUpsert(id, upsertYaml("", "17"), 409);
    }

    @Test
    void upsert_invalidInputIsRejectedAsAWhole() throws Exception {
        authenticateWith(role("upsertjob", "create"));
        String duplicates = upsertYaml("  indexMajorVersion: 1", "1").replace("- id: 'doc-2'", "- id: 'doc-1'\n  version: '2'");

        putUpsert(UUID.randomUUID(), duplicates, 400);
        putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: 9", "1"), 400);
        putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: 1", "1") + "allowRollback: true\n", 400);
        putUpsert(UUID.randomUUID(), "message: [unclosed", 400);
        String tooMany = upsertYaml("  indexMajorVersion: 1", "1") + "- id: a\n- id: b\n- id: c\n- id: d\n";
        putUpsert(UUID.randomUUID(), tooMany, 400);
        putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: 1", "1").replace("doc-2", "x".repeat(513)), 400);
        putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: 1.9", "1"), 400);
        putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: '1'", "1"), 400);
    }

    @Test
    void upsert_unquotedNumericIdIsReadAsString() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        String report = putUpsert(UUID.randomUUID(), upsertYaml("  indexMajorVersion: 1", "1").replace("'doc-2'", "12345"), 200)
                .getResponse().getContentAsString();
        assertThat(report).contains("id: \"12345\"");
    }

    @Test
    void upsert_tasksAreCreatedInTheBackground() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();
        putUpsert(id, upsertYaml("  indexMajorVersion: 1", "1"), 200);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                perform(get("/api/upsert-jobs/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("materialized-tasks: 2"));
    }

    @Test
    void report_echoesTheSubmittedSelectorAndReportsTheResolvedMajorInTarget() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        String report = putUpsert(UUID.randomUUID(), upsertYaml("", "1"), 200).getResponse().getContentAsString();

        String operation = report.substring(report.indexOf("operation:"), report.indexOf("job-id:"));
        assertThat(operation).doesNotContain("indexMajorVersion");
        assertThat(report).contains("selection: highest");
    }

    @Test
    void upsert_oversizedBodyIsRejected() throws Exception {
        authenticateWith(role("upsertjob", "create"));
        String oversized = upsertYaml("  indexMajorVersion: 1", "1") + "#" + "x".repeat(1024 * 1024) + "\n";

        String response = putUpsert(UUID.randomUUID(), oversized, 400).getResponse().getContentAsString();

        assertThat(response).contains("the maximum is 1048576");
    }

    @Test
    void upsert_nonYamlContentTypeIs415() throws Exception {
        authenticateWith(role("upsertjob", "create"));
        perform(put("/api/upsert-jobs/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void upsert_invalidUuidIs400() throws Exception {
        authenticateWith(role("upsertjob", "create"));
        perform(put("/api/upsert-jobs/not-a-uuid").contentType(YAML).content(upsertYaml("", "1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_isStoredWithoutVersionMetadata() throws Exception {
        authenticateWith(role("deletejob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();

        String report = perform(put("/api/delete-jobs/" + id).contentType("application/x-yaml").content(DELETE_YAML))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String retrieved = perform(get("/api/delete-jobs/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(report).contains("job-type: delete").contains("selection: explicit")
                .doesNotContain("version: '").doesNotContain("actual-index");
        assertThat(retrieved).contains("job-id: " + id);
    }

    @Test
    void delete_versionOnReferenceOrRollbackFieldIsRejected() throws Exception {
        authenticateWith(role("deletejob", "create"));
        perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML)
                        .content(DELETE_YAML.replace("- id: 'doc-1'", "- id: 'doc-1'\n  version: '3'")))
                .andExpect(status().isBadRequest());
        perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML)
                        .content(DELETE_YAML + "allowVersionRollback: true\n"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_multiIndexTargetIs422NotAllowed() throws Exception {
        authenticateWith(role("deletejob", "create"));
        openSearch("PUT", "/it_extra_target", "{\"aliases\":{\"" + DELETE_WRITE_ALIAS + "\":{}}}");
        try {
            String response = perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML).content(DELETE_YAML))
                    .andExpect(status().is(422)).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("NOT_ALLOWED");
        } finally {
            openSearch("DELETE", "/it_extra_target", null);
        }
    }

    @Test
    void delete_targetWithRolloverPolicyIs422NotAllowed() throws Exception {
        authenticateWith(role("deletejob", "create"));
        String physicalIndex = openSearch("GET", "/_alias/" + DELETE_WRITE_ALIAS, null).replaceAll("^\\{\"([^\"]+)\".*$", "$1");
        openSearch("PUT", "/_plugins/_ism/policies/it_rollover_policy", ROLLOVER_POLICY);
        openSearch("POST", "/_plugins/_ism/add/" + physicalIndex, "{\"policy_id\":\"it_rollover_policy\"}");
        try {
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    acceptanceCheck.check(physicalIndex).outcome() == PolicyCheckOutcome.ROLLOVER_IN_POLICY);

            String response = perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML).content(DELETE_YAML))
                    .andExpect(status().is(422)).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("NOT_ALLOWED");
        } finally {
            openSearch("POST", "/_plugins/_ism/remove/" + physicalIndex, null);
        }
    }

    @Test
    @SuppressWarnings("java:S8786")
    void delete_missingWriteAliasIs422TargetInvalid() throws Exception {
        authenticateWith(role("deletejob", "create"));
        String physicalIndex = openSearch("GET", "/_alias/" + DELETE_WRITE_ALIAS, null)
                .replaceAll("^\\{\"([^\"]+)\".*$", "$1");
        String aliasDefinition = openSearch("GET", "/" + physicalIndex + "/_alias/" + DELETE_WRITE_ALIAS, null)
                .replaceAll("^.*\"" + DELETE_WRITE_ALIAS + "\":(\\{[^}]*}).*$", "$1");
        openSearch("DELETE", "/" + physicalIndex + "/_alias/" + DELETE_WRITE_ALIAS, null);
        try {
            String response = perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML).content(DELETE_YAML))
                    .andExpect(status().is(422)).andReturn().getResponse().getContentAsString();
            assertThat(response).contains("TARGET_INVALID");
        } finally {
            openSearch("PUT", "/" + physicalIndex + "/_alias/" + DELETE_WRITE_ALIAS, aliasDefinition);
        }
    }

    private static String openSearch(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200) + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isBetween(200, 299);
            return response.body();
        }
    }

    @Test
    void report_unknownJobIs404() throws Exception {
        authenticateWith(role("jobreport", "read"));
        perform(get("/api/upsert-jobs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void report_nonNumericLimitIs400InYaml() throws Exception {
        authenticateWith(role("jobreport", "read"));
        String response = perform(get("/api/upsert-jobs/" + UUID.randomUUID()).param("limit", "many"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(YAML))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("INVALID_REQUEST").contains("limit");
    }

    @Test
    @SuppressWarnings("java:S8786")
    void report_pagesCoverEveryId() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();
        putUpsert(id, upsertYaml("", "1"), 200);

        String first = perform(get("/api/upsert-jobs/" + id).param("limit", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(first).contains("id: doc-1").doesNotContain("id: doc-2").contains("returned: 1");
        String cursor = first.lines().filter(l -> l.contains("next-cursor:")).findFirst().orElseThrow()
                .replaceAll(".*next-cursor:\\s*", "").replace("'", "").replace("\"", "").trim();
        assertThat(cursor).isNotEqualTo("null").isNotBlank();
        String second = perform(get("/api/upsert-jobs/" + id).param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).contains("id: doc-2").doesNotContain("id: doc-1");
    }

    @Test
    void openApiGroupContainsOnlyTheProtectedJobOperations() throws Exception {
        String docs = mockMvc.perform(get("/api-docs/maintenance-jobs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(docs)
                .contains("/api/upsert-jobs/{jobId}", "/api/delete-jobs/{jobId}", "\"OIDC\"")
                .doesNotContain("/actuator");
    }

    @Test
    void missingCreateRoleIs403() throws Exception {
        authenticateWith(role("jobreport", "read"));
        putUpsert(UUID.randomUUID(), upsertYaml("", "1"), 403);
        perform(put("/api/delete-jobs/" + UUID.randomUUID()).contentType(YAML).content(DELETE_YAML))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingReportRoleIs403() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("deletejob", "create"));
        perform(get("/api/upsert-jobs/" + UUID.randomUUID())).andExpect(status().isForbidden());
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void storageOutageIsReportedAsUnavailableNotNotFound() throws Exception {
        authenticateWith(role("upsertjob", "create"), role("jobreport", "read"));
        UUID id = UUID.randomUUID();
        putUpsert(id, upsertYaml("", "1"), 200);

        OPENSEARCH.stop();

        String response = perform(get("/api/upsert-jobs/" + id)).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        putUpsert(UUID.randomUUID(), upsertYaml("", "1"), 503);

        assertThat(response).contains("STORAGE_UNAVAILABLE").doesNotContain("it_jobs");
    }
}
