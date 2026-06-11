package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.domainevent.avro.AvroDomainEventBuilder;
import ch.admin.bit.jeap.messaging.kafka.contract.ContractsValidator;
import ch.admin.bit.jeap.messaging.kafka.test.KafkaIntegrationTestBase;
import ch.admin.bit.jeap.opensearch.indexwriter.adapter.kafka.KafkaIndexWriterConsumerFactory;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import ch.admin.bit.jme.declaration.DeclarationPayload;
import ch.admin.bit.jme.declaration.DeclarationReferences;
import ch.admin.bit.jme.declaration.JmeDeclarationCreatedEvent;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.togglz.core.manager.FeatureManager;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoint.prometheus.enabled=true",
        "management.endpoints.web.exposure.include=*",
        "jeap.monitor.prometheus.secure=false"})
@DirtiesContext
class IndexWriterMetricsIT extends KafkaIntegrationTestBase {

    private static final String TEST_TOPIC = "test-index-writer";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.search-item-provider.token-uri",
                () -> wireMock.baseUrl() + "/oauth/token");
        registry.add("test.wiremock.base-url", wireMock::baseUrl);
    }

    @LocalServerPort
    private int localServerPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    @SuppressWarnings("unused")
    private IndexWriter indexWriter;

    @MockitoBean
    @SuppressWarnings("unused")
    private ContractsValidator contractsValidator;

    @MockitoBean
    @SuppressWarnings("unused")
    private FeatureManager featureManager;

    @Autowired
    private KafkaIndexWriterConsumerFactory consumerFactory;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @BeforeEach
    void setUp() {
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
                                {"origin":null,"data":{}}
                                """)));
    }

    @Test
    void indexingTimerMetricIsExposedOnPrometheusEndpointAfterProcessingMessage() {
        sendSync(TEST_TOPIC, declarationEvent("metrics-1"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var response = restTemplate.getForEntity("/actuator/prometheus", String.class);
            assertEquals(200, response.getStatusCode().value());
            assertThat(response.getBody())
                    .contains(
                            "jeap_opensearch_indexwriter_indexing_seconds_sum{message_type=\"JmeDeclarationCreatedEvent\"",
                            "jeap_opensearch_indexwriter_indexing_seconds_count{message_type=\"JmeDeclarationCreatedEvent\""
                    );
        });
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
