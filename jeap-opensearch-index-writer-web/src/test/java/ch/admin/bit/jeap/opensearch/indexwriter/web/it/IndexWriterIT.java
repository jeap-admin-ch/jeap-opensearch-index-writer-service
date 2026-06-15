package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.domainevent.avro.AvroDomainEventBuilder;
import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.KafkaIndexWriterConsumerFactory;
import ch.admin.bit.jme.declaration.DeclarationPayload;
import ch.admin.bit.jme.declaration.DeclarationReferences;
import ch.admin.bit.jme.declaration.JmeDeclarationCreatedEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.ExistsRequest;
import org.opensearch.client.opensearch.core.GetRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class IndexWriterIT extends KafkaIntegrationTestBase {

    private static final String TEST_TOPIC = "test-index-writer";
    private static final String ORIGIN_ID = "doc-1";
    private static final String INDEX_WRITE_ALIAS = "test_document_v1_write";
    private static final String DELETE_INDEX_WRITE_ALIAS = "test_document_delete_v1_write";
    private static final String TRUE_CONDITION_INDEX_WRITE_ALIAS = "test_document_true_condition_v1_write";
    private static final String FALSE_CONDITION_INDEX_WRITE_ALIAS = "test_document_false_condition_v1_write";
    private static final String FEATURE_FLAG_INDEX_WRITE_ALIAS = "test_document_feature_flag_v1_write";
    private static final Map<String, String> WRITE_TO_READ_ALIAS = Map.of(
            INDEX_WRITE_ALIAS, "test_document_read",
            DELETE_INDEX_WRITE_ALIAS, "test_document_delete_read",
            TRUE_CONDITION_INDEX_WRITE_ALIAS, "test_document_true_condition_read",
            FALSE_CONDITION_INDEX_WRITE_ALIAS, "test_document_false_condition_read",
            FEATURE_FLAG_INDEX_WRITE_ALIAS, "test_document_feature_flag_read");
    private static final NamedFeature IT_FEATURE_FLAG = new NamedFeature("IT_FEATURE_FLAG_INDEXING");

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

    // Plain (no snake_case) client used for reading back documents in assertions, so key names
    // come back exactly as stored in OpenSearch without any naming-strategy transformation.
    private static OpenSearchClient plainOpenSearchClient;

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
        createIaCTemplates();
    }

    // Runs before Spring context starts (called from @DynamicPropertySource), so IndexMappingUpdater
    // finds the templates on startup just like it would in production after IaC has run.
    private static void createIaCTemplates() {
        var transport = ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("http", OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200)))
                .build();
        plainOpenSearchClient = new OpenSearchClient(transport);
        WRITE_TO_READ_ALIAS.forEach((writeAlias, readAlias) -> {
            String templateName = writeAlias.endsWith("_write")
                    ? writeAlias.substring(0, writeAlias.length() - "_write".length())
                    : writeAlias;
            try {
                plainOpenSearchClient.indices().putIndexTemplate(r -> r
                        .name(templateName)
                        .indexPatterns(List.of(templateName + "-*"))
                        .template(t -> t.aliases(Map.of(readAlias, new Alias.Builder().build()))));
            } catch (Exception e) {
                throw new RuntimeException("Failed to create IaC template for " + writeAlias, e);
            }
        });
    }

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private FeatureManager featureManager;

    @Autowired
    private KafkaIndexWriterConsumerFactory consumerFactory;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @BeforeEach
    void setUp() throws IOException {
        listenerEndpointRegistry.getListenerContainers().forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !consumerFactory.getContainers().isEmpty());
        consumerFactory.getContainers().forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));

        wireMock.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"mock-token","token_type":"Bearer","expires_in":3600}
                                """)));

        wireMock.stubFor(get(urlPathEqualTo("/index-api/searchitems"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("index-major-version", "1")
                        .withHeader("index-minor-version", "0")
                        .withBody("""
                                {"origin":{"id":"doc-1"},"data":{}}
                                """)));

        featureManager.disable(IT_FEATURE_FLAG);

        // Pre-create the document so the delete test can verify it is removed
        indexDocument(DELETE_INDEX_WRITE_ALIAS, ORIGIN_ID);

        // Ensure all other aliases start without the test document
        deleteDocument(INDEX_WRITE_ALIAS, ORIGIN_ID);
        deleteDocument(TRUE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID);
        deleteDocument(FALSE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID);
        deleteDocument(FEATURE_FLAG_INDEX_WRITE_ALIAS, ORIGIN_ID);
    }

    @Test
    void upsertWithoutConditionTriggersIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-no-condition"));

        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void deleteWithoutConditionTriggersIndexDelete() {
        sendSync(TEST_TOPIC, declarationEvent("delete-no-condition"));

        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> !documentExists(DELETE_INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void upsertWithConditionEvaluatingToTrueTriggersIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-true-condition"));

        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(TRUE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void upsertWithConditionEvaluatingToFalseSkipsIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-false-condition"));

        // Wait for TrueCondition (op 3) to confirm the entire message was processed
        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(TRUE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID));

        assertFalse(documentExists(FALSE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void upsertWithActiveFeatureFlagTriggersIndexWrite() {
        featureManager.enable(IT_FEATURE_FLAG);

        sendSync(TEST_TOPIC, declarationEvent("upsert-feature-flag-enabled"));

        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(FEATURE_FLAG_INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void upsertWithInactiveFeatureFlagSkipsIndexWrite() {
        featureManager.disable(IT_FEATURE_FLAG);

        sendSync(TEST_TOPIC, declarationEvent("upsert-feature-flag-disabled"));

        // Wait for TrueCondition (op 3) to confirm the entire message was processed
        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(TRUE_CONDITION_INDEX_WRITE_ALIAS, ORIGIN_ID));

        assertFalse(documentExists(FEATURE_FLAG_INDEX_WRITE_ALIAS, ORIGIN_ID));
    }

    @Test
    void upsertWithTypedDataClass_writesSnakeCaseFieldNamesFromResourceService() throws IOException {
        wireMock.stubFor(get(urlPathEqualTo("/index-api/searchitems"))
                .withQueryParam("origin_id", equalTo(ORIGIN_ID))
                .atPriority(1)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("index-major-version", "1")
                        .withHeader("index-minor-version", "0")
                        .withBody("""
                                {"origin":{"id":"doc-1"},"data":{"camel_case_field":"value","another_camel_field":"test"}}
                                """)));

        sendSync(TEST_TOPIC, declarationEvent("upsert-typed-data-class"));

        await().atMost(Duration.ofMillis(TEST_TIMEOUT))
                .until(() -> documentExists(INDEX_WRITE_ALIAS, ORIGIN_ID));

        ObjectNode source = getDocumentSource(INDEX_WRITE_ALIAS, ORIGIN_ID);
        ObjectNode data = (ObjectNode) source.get("data");
        assertThat(data.has("camel_case_field")).isTrue();
        assertThat(data.get("camel_case_field").asText()).isEqualTo("value");
        assertThat(data.has("another_camel_field")).isTrue();
        assertThat(data.has("camelCaseField")).isFalse();
        assertThat(data.has("anotherCamelField")).isFalse();
    }

    private boolean documentExists(String alias, String docId) {
        try {
            return openSearchClient.exists(new ExistsRequest.Builder()
                    .index(alias)
                    .id(docId)
                    .build()).value();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ObjectNode getDocumentSource(String alias, String docId) throws IOException {
        return plainOpenSearchClient.get(
                GetRequest.of(r -> r.index(alias).id(docId)),
                ObjectNode.class
        ).source();
    }

    private void indexDocument(String alias, String docId) throws IOException {
        openSearchClient.index(new IndexRequest.Builder<Map<String, String>>()
                .index(alias)
                .id(docId)
                .document(Map.of("id", docId))
                .build());
    }

    private void deleteDocument(String alias, String docId) throws IOException {
        openSearchClient.delete(new DeleteRequest.Builder()
                .index(alias)
                .id(docId)
                .build());
    }

    private static JmeDeclarationCreatedEvent declarationEvent(String idempotenceId) {
        return new TestEventBuilder()
                .idempotenceId(idempotenceId)
                .build();
    }

    private static class TestEventBuilder extends AvroDomainEventBuilder<TestEventBuilder, JmeDeclarationCreatedEvent> {

        TestEventBuilder() {
            super(JmeDeclarationCreatedEvent::new);
        }

        @Override
        protected String getServiceName() {
            return "jeap-opensearch-index-writer-it";
        }

        @Override
        protected String getSystemName() {
            return "JME";
        }

        @Override
        protected TestEventBuilder self() {
            return this;
        }

        @Override
        public JmeDeclarationCreatedEvent build() {
            setReferences(DeclarationReferences.newBuilder().build());
            setPayload(DeclarationPayload.newBuilder().setMessage("test").build());
            return super.build();
        }
    }
}

