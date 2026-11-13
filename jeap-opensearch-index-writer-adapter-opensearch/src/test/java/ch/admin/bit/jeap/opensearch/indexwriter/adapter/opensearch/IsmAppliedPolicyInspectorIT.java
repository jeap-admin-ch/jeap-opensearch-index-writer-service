package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotConcreteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IsmAppliedPolicyInspectorIT {

    private static final String ROLLING_ALIAS = "policy_rolling_v1_write";
    private static final String NON_ROLLING_ALIAS = "policy_non_rolling_v1_write";
    private static final String UNMANAGED_ALIAS = "policy_unmanaged_v1_write";
    private static final String CHANGED_ALIAS = "policy_changed_v1_write";
    private static final String MAPPING = """
            {"mappings":{"dynamic":false,"properties":{"data":{"properties":{"title":{"type":"keyword"}}}}}}
            """;
    // A rollover in a later state must be found too, not only in the current one
    private static final String ROLLOVER_POLICY = """
            {"policy":{"description":"rolling","default_state":"hot","states":[
              {"name":"hot","actions":[],"transitions":[{"state_name":"warm","conditions":{"min_index_age":"30d"}}]},
              {"name":"warm","actions":[{"rollover":{"min_doc_count":1000000}}],"transitions":[]}]}}
            """;
    private static final String NO_ROLLOVER_POLICY = """
            {"policy":{"description":"non rolling","default_state":"hot","states":[
              {"name":"hot","actions":[{"replica_count":{"number_of_replicas":0}}],"transitions":[]}]}}
            """;
    private static final Duration ISM_INITIALIZATION_TIMEOUT = Duration.ofMinutes(3);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("docker-hub.nexus.bit.admin.ch/opensearchproject/opensearch:3.3.2")
                    .asCompatibleSubstituteFor("opensearchproject/opensearch"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            // ISM attaches a policy asynchronously; a short job interval keeps the wait for initialization short
            .withEnv("plugins.index_state_management.job_interval", "1")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static OpenSearchClient client;
    private static OpenSearchIndexWriter indexWriter;
    private static IsmAppliedPolicyInspector ismInspector;

    private JobTargetAcceptanceCheck check;

    @BeforeAll
    static void setUpIndices() throws IOException {
        JsonMapper jsonMapper = JsonMapper.builder().disable(WRITE_DATES_AS_TIMESTAMPS).build();
        client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("http", OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200)))
                .setMapper(new JacksonJsonpMapper(jsonMapper))
                .build());
        DataFieldValidator dataFieldValidator = new DataFieldValidator(jsonMapper);
        indexWriter = new OpenSearchIndexWriter(client, dataFieldValidator, new IndexTemplateManager(client),
                new PhysicalIndexManager(client), new IndexMappingManager(client, dataFieldValidator), new IndexAnalysisCompatibility(client));
        ismInspector = new IsmAppliedPolicyInspector(client, jsonMapper);

        for (String alias : new String[]{ROLLING_ALIAS, NON_ROLLING_ALIAS, UNMANAGED_ALIAS, CHANGED_ALIAS}) {
            indexWriter.ensureIndexReady(alias, alias.replace("_write", "_read"), 0,
                    () -> new ByteArrayInputStream(MAPPING.getBytes(StandardCharsets.UTF_8)), new IndexTemplateSettings(1, 0, "1s"));
        }
        ism("PUT", "/_plugins/_ism/policies/rollover_policy", ROLLOVER_POLICY);
        ism("PUT", "/_plugins/_ism/policies/no_rollover_policy", NO_ROLLOVER_POLICY);
        attach(target(ROLLING_ALIAS), "rollover_policy");
        attach(target(NON_ROLLING_ALIAS), "no_rollover_policy");
        attach(target(CHANGED_ALIAS), "no_rollover_policy");
        awaitOutcome(target(ROLLING_ALIAS), PolicyCheckOutcome.ROLLOVER_IN_POLICY);
        awaitOutcome(target(NON_ROLLING_ALIAS), PolicyCheckOutcome.NO_ROLLOVER_IN_POLICY);
        awaitOutcome(target(CHANGED_ALIAS), PolicyCheckOutcome.NO_ROLLOVER_IN_POLICY);
    }

    @BeforeEach
    void setUp() {
        check = new JobTargetAcceptanceCheck(ismInspector, new SimpleMeterRegistry());
    }

    @Test
    void refusesTargetWhoseAppliedPolicyHasRollover() {
        JobTargetAcceptance acceptance = check.check(target(ROLLING_ALIAS));

        assertThat(acceptance.allowed()).isFalse();
        assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.ROLLOVER_IN_POLICY);
        assertThat(acceptance.policyId()).isEqualTo("rollover_policy");
        assertThat(acceptance.reason()).contains(target(ROLLING_ALIAS), "rollover_policy", "[warm]");
        assertThatThrownBy(acceptance::requireAllowed).isInstanceOf(JobTargetNotAllowedException.class);
    }

    @Test
    void allowsTargetWhosePolicyHasNoRollover() {
        JobTargetAcceptance acceptance = check.check(target(NON_ROLLING_ALIAS));

        assertThat(acceptance.allowed()).isTrue();
        assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.NO_ROLLOVER_IN_POLICY);
        assertThat(acceptance.policyId()).isEqualTo("no_rollover_policy");
    }

    @Test
    void allowsTargetWithoutAttachedPolicy() {
        JobTargetAcceptance acceptance = check.check(target(UNMANAGED_ALIAS));

        assertThat(acceptance.allowed()).isTrue();
        assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.NO_POLICY_APPLIED);
    }

    // Runs last: closing the shared ISM config index disturbs ISM for every other test in this container
    @Test
    @Order(Integer.MAX_VALUE)
    void allowsTargetWhenPolicyMetadataCannotBeRead() throws IOException {
        // Explain reads the applied policy from the ISM config index; while it is closed, explain fails for every index
        ism("POST", "/.opendistro-ism-config/_close", null);
        try {
            JobTargetAcceptance acceptance = check.check(target(ROLLING_ALIAS));

            assertThat(acceptance.allowed()).isTrue();
            assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
            assertThat(acceptance.reason()).contains("index_closed_exception", "without verifying non-rolling operation");
        } finally {
            ism("POST", "/.opendistro-ism-config/_open", null);
            // _open returns before the shard is started; later explain calls would otherwise fail intermittently
            ism("GET", "/_cluster/health/.opendistro-ism-config?wait_for_status=yellow&timeout=60s", null);
        }
    }

    @Test
    void refusesWriteAliasOfRollingIndexAsNotConcrete() {
        assertThatThrownBy(() -> check.check(ROLLING_ALIAS))
                .isInstanceOfSatisfying(JobTargetNotConcreteException.class, e -> {
                    assertThat(e).hasMessageContaining("not a concrete index").hasMessageContaining(target(ROLLING_ALIAS));
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void missingTargetKeepsOrdinaryErrorHandling() {
        assertThatThrownBy(() -> check.check("policy_missing_v1-000001"))
                .isInstanceOf(JobTargetNotFoundException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void recheckAfterPolicyChangeRefusesTarget() throws IOException {
        String target = target(CHANGED_ALIAS);
        check.check(target).requireAllowed();

        ism("POST", "/_plugins/_ism/remove/" + target, null);
        attach(target, "rollover_policy");
        awaitOutcome(target, PolicyCheckOutcome.ROLLOVER_IN_POLICY);

        assertThat(check.check(target).allowed()).isFalse();
    }

    private static String target(String writeAlias) {
        return IndexNaming.initialPhysicalIndexName(writeAlias);
    }

    private static void attach(String index, String policyId) throws IOException {
        ism("POST", "/_plugins/_ism/add/" + index, "{\"policy_id\":\"%s\"}".formatted(policyId));
    }

    // While ISM initializes, explain can report no policy or only its id; wait for the fully applied definition
    private static void awaitOutcome(String index, PolicyCheckOutcome expected) {
        await().atMost(ISM_INITIALIZATION_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptionsMatching(e -> e instanceof IndexWriterException ex && ex.isRetryable())
                .until(() -> ismInspector.inspect(index).outcome() == expected);
    }

    private static void ism(String method, String endpoint, String json) throws IOException {
        var request = Requests.builder().method(method).endpoint(endpoint);
        if (json != null) {
            request.json(json);
        }
        try (Response response = client.generic().execute(request.build())) {
            String body = response.getBody().map(Body::bodyAsString).orElse("");
            assertThat(response.getStatus()).as("%s %s: %s", method, endpoint, body).isBetween(200, 299);
            // ISM add and remove report per-index failures with HTTP 200
            assertThat(JSON.readTree(body).path("failures").asBoolean(false)).as("%s %s: %s", method, endpoint, body).isFalse();
        }
    }
}
