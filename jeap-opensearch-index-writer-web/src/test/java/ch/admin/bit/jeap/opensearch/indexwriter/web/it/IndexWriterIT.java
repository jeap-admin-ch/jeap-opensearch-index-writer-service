package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.domainevent.avro.AvroDomainEventBuilder;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class IndexWriterIT extends KafkaIntegrationTestBase {

    private static final String TEST_TOPIC = "test-index-writer";
    private static final String ORIGIN_ID = "doc-1";
    private static final String INDEX_WRITE_ALIAS = "TestDocument_V1_write";
    private static final String TRUE_CONDITION_INDEX_WRITE_ALIAS = "TestDocumentTrueCondition_V1_write";
    private static final String FALSE_CONDITION_INDEX_WRITE_ALIAS = "TestDocumentFalseCondition_V1_write";
    private static final String FEATURE_FLAG_INDEX_WRITE_ALIAS = "TestDocumentFeatureFlag_V1_write";
    private static final NamedFeature IT_FEATURE_FLAG = new NamedFeature("IT_FEATURE_FLAG_INDEXING");

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

    @MockitoBean
    private IndexWriter indexWriter;

    @Autowired
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
    void upsertWithoutConditionTriggersIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-no-condition"));

        verify(indexWriter, timeout(TEST_TIMEOUT)).upsertSearchItem(eq(INDEX_WRITE_ALIAS), eq(ORIGIN_ID), any());
    }

    @Test
    void deleteWithoutConditionTriggersIndexDelete() {
        sendSync(TEST_TOPIC, declarationEvent("delete-no-condition"));

        verify(indexWriter, timeout(TEST_TIMEOUT)).deleteSearchItem(INDEX_WRITE_ALIAS, ORIGIN_ID);
    }

    @Test
    void upsertWithConditionEvaluatingToTrueTriggersIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-true-condition"));

        verify(indexWriter, timeout(TEST_TIMEOUT)).upsertSearchItem(eq(TRUE_CONDITION_INDEX_WRITE_ALIAS), eq(ORIGIN_ID), any());
    }

    @Test
    void upsertWithConditionEvaluatingToFalseSkipsIndexWrite() {
        sendSync(TEST_TOPIC, declarationEvent("upsert-false-condition"));

        // Wait for the no-condition operation to complete, confirming the message was processed
        verify(indexWriter, timeout(TEST_TIMEOUT)).upsertSearchItem(eq(INDEX_WRITE_ALIAS), eq(ORIGIN_ID), any());

        verify(indexWriter, never()).upsertSearchItem(eq(FALSE_CONDITION_INDEX_WRITE_ALIAS), any(), any());
    }

    @Test
    void upsertWithActiveFeatureFlagTriggersIndexWrite() {
        featureManager.enable(IT_FEATURE_FLAG);

        sendSync(TEST_TOPIC, declarationEvent("upsert-feature-flag-enabled"));

        verify(indexWriter, timeout(TEST_TIMEOUT)).upsertSearchItem(eq(FEATURE_FLAG_INDEX_WRITE_ALIAS), eq(ORIGIN_ID), any());
    }

    @Test
    void upsertWithInactiveFeatureFlagSkipsIndexWrite() {
        featureManager.disable(IT_FEATURE_FLAG);

        sendSync(TEST_TOPIC, declarationEvent("upsert-feature-flag-disabled"));

        // Wait for the no-condition operation to complete, confirming the message was processed
        verify(indexWriter, timeout(TEST_TIMEOUT)).upsertSearchItem(eq(INDEX_WRITE_ALIAS), eq(ORIGIN_ID), any());

        verify(indexWriter, never()).upsertSearchItem(eq(FEATURE_FLAG_INDEX_WRITE_ALIAS), any(), any());
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
