package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotConcreteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

/**
 * Runs the policy inspection with the security plugin enabled, as a service principal that holds exactly the
 * permissions documented in docs/opensearch-permissions.md.
 */
@Testcontainers
class IsmAppliedPolicyInspectorSecuredIT {

    private static final String ADMIN_PASSWORD = "Str0ng!Passw0rd#1";
    private static final String USER_PASSWORD = "Str0ng!Passw0rd#2";
    private static final String ROLLING_ALIAS = "secured_rolling_v1_write";
    private static final String MAPPING = """
            {"mappings":{"dynamic":false,"properties":{"data":{"properties":{"title":{"type":"keyword"}}}}}}
            """;
    private static final String ROLLOVER_POLICY = """
            {"policy":{"description":"rolling","default_state":"hot","states":[
              {"name":"hot","actions":[{"rollover":{"min_doc_count":1000000}}],"transitions":[]}]}}
            """;
    private static final String SERVICE_ROLE = """
            {"cluster_permissions":["indices:admin/index_template/put","indices:admin/aliases/get","indices:data/write/bulk",
              "cluster:admin/opendistro/ism/managedindex/explain"],
             "index_permissions":[{"index_patterns":["*"],"allowed_actions":["indices:admin/create","indices:admin/aliases",
              "indices:admin/aliases/get","indices:admin/mappings/get","indices:monitor/settings/get","indices:admin/mapping/put",
              "indices:data/write/bulk*","indices:data/write/bulk","indices:data/write/index","indices:data/write/delete",
              "indices:data/read/get","indices:data/read/search","indices:admin/get"]}]}
            """;
    private static final String ROLE_WITHOUT_EXPLAIN = """
            {"index_permissions":[{"index_patterns":["*"],"allowed_actions":["indices:admin/get"]}]}
            """;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("docker-hub.nexus.bit.admin.ch/opensearchproject/opensearch:3.3.2")
                    .asCompatibleSubstituteFor("opensearchproject/opensearch"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withEnv("plugins.index_state_management.job_interval", "1")
            // No HTTP wait strategy: its HttpsURLConnection honors the JVM proxy settings on CI and fails with a proxy 403
            .waitingFor(Wait.forLogMessage(".*Node '.*' initialized.*\\n", 1).withStartupTimeout(Duration.ofMinutes(3)));

    private static JsonMapper jsonMapper;
    private static OpenSearchClient adminClient;
    private static String target;

    @BeforeAll
    static void setUp() throws IOException, GeneralSecurityException {
        jsonMapper = JsonMapper.builder().disable(WRITE_DATES_AS_TIMESTAMPS).build();
        adminClient = client("admin", ADMIN_PASSWORD);
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> adminClient.generic().execute(Requests.builder().method("GET").endpoint("/").build()).getStatus() == 200);
        DataFieldValidator dataFieldValidator = new DataFieldValidator(jsonMapper);
        new OpenSearchIndexWriter(adminClient, dataFieldValidator, new IndexTemplateManager(adminClient),
                new PhysicalIndexManager(adminClient), new IndexMappingManager(adminClient, dataFieldValidator),
                new IndexAnalysisCompatibility(adminClient))
                .ensureIndexReady(ROLLING_ALIAS, ROLLING_ALIAS.replace("_write", "_read"), 0,
                        () -> new ByteArrayInputStream(MAPPING.getBytes(StandardCharsets.UTF_8)), new IndexTemplateSettings(1, 0, "1s"));
        target = IndexNaming.initialPhysicalIndexName(ROLLING_ALIAS);
        call("PUT", "/_plugins/_ism/policies/rollover_policy", ROLLOVER_POLICY);
        call("POST", "/_plugins/_ism/add/" + target, "{\"policy_id\":\"rollover_policy\"}");
        createUser("service", SERVICE_ROLE);
        createUser("noexplain", ROLE_WITHOUT_EXPLAIN);
        IsmAppliedPolicyInspector admin = new IsmAppliedPolicyInspector(adminClient, jsonMapper);
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2))
                .until(() -> admin.inspect(target).outcome() == PolicyCheckOutcome.ROLLOVER_IN_POLICY);
    }

    @Test
    void documentedServiceRoleSeesRolloverPolicyOnSecuredCluster() throws GeneralSecurityException {
        IsmAppliedPolicyInspector inspector = new IsmAppliedPolicyInspector(client("service", USER_PASSWORD), jsonMapper);

        assertThat(inspector.inspect(target).outcome()).isEqualTo(PolicyCheckOutcome.ROLLOVER_IN_POLICY);
    }

    @Test
    void roleWithoutExplainPermissionIsUndetermined() throws GeneralSecurityException {
        IsmAppliedPolicyInspector inspector = new IsmAppliedPolicyInspector(client("noexplain", USER_PASSWORD), jsonMapper);

        assertThat(inspector.inspect(target).outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
    }

    @Test
    void startupVerifierDetectsMissingExplainPermission() throws GeneralSecurityException {
        assertThat(new IsmExplainPermissionVerifier(client("service", USER_PASSWORD)).verify()).isTrue();
        assertThat(new IsmExplainPermissionVerifier(client("noexplain", USER_PASSWORD)).verify()).isFalse();
    }

    @Test
    void aliasTargetIsRejectedEvenWhenExplainIsDenied() throws GeneralSecurityException {
        IsmAppliedPolicyInspector inspector = new IsmAppliedPolicyInspector(client("noexplain", USER_PASSWORD), jsonMapper);

        assertThatThrownBy(() -> inspector.inspect(ROLLING_ALIAS))
                .isInstanceOf(JobTargetNotConcreteException.class)
                .hasMessageContaining("not a concrete index").hasMessageContaining(target);
    }

    private static void createUser(String name, String roleJson) throws IOException {
        call("PUT", "/_plugins/_security/api/roles/" + name, roleJson);
        call("PUT", "/_plugins/_security/api/internalusers/" + name, "{\"password\":\"%s\"}".formatted(USER_PASSWORD));
        call("PUT", "/_plugins/_security/api/rolesmapping/" + name, "{\"users\":[\"%s\"]}".formatted(name));
    }

    // An extended trust manager is needed: the JDK wraps plain X509TrustManagers and still checks the host name
    // against the demo certificate, which fails for the Docker host IP on CI
    @SuppressWarnings({"java:S4830", "java:S5527"})
    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // test-only: the demo certificate of the throwaway container is self-signed
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // test-only
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // test-only
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // test-only
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // test-only
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // test-only
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static SSLContext trustAllContext() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAllManager()}, null);
        return context;
    }

    private static OpenSearchClient client(String user, String password) throws GeneralSecurityException {
        var tls = ClientTlsStrategyBuilder.create()
                .setSslContext(trustAllContext())
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .buildAsync();
        String basic = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("https", OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200)))
                .setMapper(new JacksonJsonpMapper(jsonMapper))
                .setHttpClientConfigCallback((HttpAsyncClientBuilder b) -> b
                        .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create().setTlsStrategy(tls).build())
                        .setDefaultHeaders(List.of(new BasicHeader("Authorization", "Basic " + basic))))
                .build());
    }

    private static void call(String method, String endpoint, String json) throws IOException {
        try (Response response = adminClient.generic().execute(Requests.builder().method(method).endpoint(endpoint).json(json).build())) {
            assertThat(response.getStatus())
                    .as("%s %s: %s", method, endpoint, response.getBody().map(Body::bodyAsString).orElse(""))
                    .isBetween(200, 299);
        }
    }
}
