package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.ExistsRequest;
import org.opensearch.client.opensearch.indices.*;
import org.opensearch.client.opensearch.indices.get_mapping.IndexMappingRecord;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

@Testcontainers
class OpenSearchIndexWriterIT {

    private static final String ANALYSIS_JSON = """
            {
              "settings":{"analysis":{
                "analyzer":{"folding_analyzer":{"type":"custom","tokenizer":"standard","filter":["lowercase","asciifolding"]},
                            "configured":{"type":"custom","tokenizer":"words","filter":["length_limit"],"char_filter":["symbols"]}},
                "normalizer":{"folding_normalizer":{"type":"custom","filter":["lowercase","asciifolding"]}},
                "tokenizer":{"words":{"type":"pattern","pattern":"\\\\W+"}},
                "filter":{"length_limit":{"type":"length","min":2,"max":100}},
                "char_filter":{"symbols":{"type":"mapping","mappings":["& => and"]}}
              }},
              "mappings":{"dynamic":false,"properties":{"data":{"properties":{
                "name":{"type":"text","analyzer":"folding_analyzer"},
                "code":{"type":"keyword","normalizer":"folding_normalizer"},
                "description":{"type":"text","analyzer":"configured"}
              }}}}
            }
            """;

    @Test
    void nativeAnalysisSupportsSearchRestartMinorUpdateAndRollover() throws Exception {
        String alias = "folding_v1_write";
        ensureDefinition(alias, 0, ANALYSIS_JSON);
        indexWriter.upsertSearchItem(alias, "folded", buildSearchItemWithData(
                Map.of("name", "Café Müller", "code", "ÉCOLE", "description", "foo & bar")));
        client.indices().refresh(r -> r.index(alias));

        var result = client.search(r -> r.index(alias)
                .query(q -> q.match(m -> m.field("data.name").query(v -> v.stringValue("cafe muller")))), JsonData.class);
        assertThat(result.hits().hits()).hasSize(1);
        assertThat(result.hits().hits().getFirst().source().toJson().asJsonObject().getJsonObject("data").getString("name"))
                .isEqualTo("Café Müller");
        assertThat(client.indices().analyze(r -> r.index(alias).analyzer("folding_analyzer").text("Café Müller"))
                .tokens()).extracting(t -> t.token()).containsExactly("cafe", "muller");
        assertThat(client.indices().analyze(r -> r.index(alias).normalizer("folding_normalizer").text("ÉCOLE"))
                .tokens()).extracting(t -> t.token()).containsExactly("ecole");
        assertThat(client.indices().analyze(r -> r.index(alias).analyzer("configured").text("foo & bar"))
                .tokens()).extracting(t -> t.token()).containsExactly("foo", "and", "bar");

        ensureDefinition(alias, 0, ANALYSIS_JSON);
        String minor = ANALYSIS_JSON.replace("\"name\":{", "\"additional\":{\"type\":\"text\",\"analyzer\":\"folding_analyzer\"},\"name\":{");
        ensureDefinition(alias, 1, minor);
        assertThat(httpGet(openSearchUrl + "/folding_v1-000001/_mapping")).contains("additional");
        assertThat(client.indices().rollover(r -> r.alias(alias)).rolledOver()).isTrue();
        assertThat(httpGet(openSearchUrl + "/folding_v1-000002/_settings")).contains("folding_analyzer", "folding_normalizer", "length_limit");
        ensureDefinition(alias, 1, minor);
        assertThat(client.indices().analyze(r -> r.index("folding_v1-000002").analyzer("folding_analyzer").text("Café Müller"))
                .tokens()).extracting(t -> t.token()).containsExactly("cafe", "muller");
    }

    @Test
    void incompatibleAnalysisFailsBeforeTemplateMutationAndMajorCreatesNewIndex() throws Exception {
        String alias = "compatibility_v1_write";
        ensureDefinition(alias, 0, ANALYSIS_JSON);
        String template = httpGet(openSearchUrl + "/_index_template/compatibility_v1");
        String changed = ANALYSIS_JSON.replace("\"lowercase\",\"asciifolding\"", "\"lowercase\"");
        assertThatThrownBy(() -> ensureDefinition(alias, 1, changed))
                .isInstanceOf(OpenSearchIndexWriterException.class).hasMessageContaining("new IndexType major");
        assertThat(httpGet(openSearchUrl + "/_index_template/compatibility_v1")).isEqualTo(template);
        assertThatThrownBy(() -> ensureDefinition(alias, 0, changed))
                .hasMessageContaining("settings.analysis");
        String reassigned = ANALYSIS_JSON.replace("\"analyzer\":\"folding_analyzer\"", "\"analyzer\":\"standard\"");
        assertThatThrownBy(() -> ensureDefinition(alias, 1, reassigned)).hasMessageContaining("name.analyzer");
        assertThat(httpGet(openSearchUrl + "/_index_template/compatibility_v1")).isEqualTo(template);
        ensureDefinition("compatibility_v2_write", 0, changed);
        assertThat(client.indices().exists(r -> r.index("compatibility_v2-000001")).value()).isTrue();
    }

    @Test
    void legacyIndicesCannotSilentlyAcquireAnalysis() {
        ensureDefinition("legacy_v1_write", 0, MAPPING_JSON);
        assertThatThrownBy(() -> ensureDefinition("legacy_v1_write", 1, ANALYSIS_JSON))
                .hasMessageContaining("settings.analysis");
    }

    @Test
    void explicitDefaultSearchAnalyzersSurviveRestart() throws IOException {
        String definition = ANALYSIS_JSON.replace("\"name\":{\"type\":\"text\",\"analyzer\":\"folding_analyzer\"}",
                "\"name\":{\"type\":\"text\",\"analyzer\":\"folding_analyzer\",\"search_analyzer\":\"folding_analyzer\",\"search_quote_analyzer\":\"folding_analyzer\"}");
        ensureDefinition("explicit_search_v1_write", 0, definition);
        ensureDefinition("explicit_search_v1_write", 0, definition);
        String implicit = """
                {"mappings":{"dynamic":false,"properties":{"name":{"type":"text","search_analyzer":"standard"}}}}
                """;
        ensureDefinition("implicit_search_v1_write", 0, implicit);
        ensureDefinition("implicit_search_v1_write", 0, implicit);

        assertThat(client.indices().exists(r -> r.index("explicit_search_v1-000001")).value()).isTrue();
        assertThat(client.indices().exists(r -> r.index("implicit_search_v1-000001")).value()).isTrue();
    }

    @Test
    void addingNativeAnalysisToAnOpenIndexRequiresLifecycleChange() throws Exception {
        client.indices().create(r -> r.index("analysis_spike"));
        var request = HttpRequest.newBuilder(URI.create(openSearchUrl + "/analysis_spike/_settings"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"analysis":{"analyzer":{"new_analyzer":{"type":"custom","tokenizer":"standard"}},
                                     "normalizer":{"new_normalizer":{"type":"custom","filter":["lowercase"]}}}}
                        """)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("non dynamic settings", "open indices");
    }

    @Test
    void unknownAnalyzerReferenceIsRejectedByOpenSearch() {
        String invalidDefinition = ANALYSIS_JSON.replace(
                "\"analyzer\":\"folding_analyzer\"", "\"analyzer\":\"missing_analyzer\"");

        assertThatThrownBy(() -> ensureDefinition("invalid_analysis_v1_write", 0, invalidDefinition))
                .isInstanceOf(OpenSearchIndexWriterException.class);
    }

    private void ensureDefinition(String alias, int minor, String definition) {
        indexWriter.ensureIndexReady(alias, "analysis_read", minor,
                () -> new ByteArrayInputStream(definition.getBytes(StandardCharsets.UTF_8)), new IndexTemplateSettings(1, 0, "1s"));
    }

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";
    private static final String INDEX_READ_ALIAS = "orders_read";
    private static final String PHYSICAL_INDEX = "orders_v1-000001";
    private static final String STRUCTURE_WRITE_ALIAS = "decrees_v1_write";
    private static final String STRUCTURE_READ_ALIAS = "decrees_read";
    private static final String STRUCTURE_INDEX = "decrees_v1-000001";
    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 3;

    private static final String MAPPING_JSON = """
            {
              "mappings": {
                "dynamic": false,
                "_meta": {
                  "schema_version": 99,
                  "custom": 42,
                  "jeap": { "collection_fields": ["order_id"] }
                },
                "properties": {
                  "order_id": { "type": "keyword" }
                }
              }
            }
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

    private static String openSearchUrl;
    private static OpenSearchClient client;
    private static OpenSearchIndexWriter indexWriter;

    @BeforeAll
    static void setup() throws Exception {
        openSearchUrl = "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200);
        String url = openSearchUrl;
        JsonMapper jsonMapper = JsonMapper.builder()
                .disable(WRITE_DATES_AS_TIMESTAMPS)
                .build();

        var transport = ApacheHttpClient5TransportBuilder
                .builder(HttpHost.create(url))
                .setMapper(new JacksonJsonpMapper(jsonMapper))
                .build();
        client = new OpenSearchClient(transport);
        DataFieldValidator dataFieldValidator = new DataFieldValidator(jsonMapper);
        IndexTemplateManager indexTemplateManager = new IndexTemplateManager(client);
        PhysicalIndexManager physicalIndexManager = new PhysicalIndexManager(client);
        IndexMappingManager indexMappingManager = new IndexMappingManager(client, dataFieldValidator);
        indexWriter = new OpenSearchIndexWriter(client, dataFieldValidator, indexTemplateManager, physicalIndexManager, indexMappingManager,
                new IndexAnalysisCompatibility(client));

        // Service creates the template and initial physical index (000001) on first ensureIndexReady call.
        // Mapping with dynamic: false is applied immediately so OpenSearch does not auto-map data fields.
        indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));
        indexWriter.ensureIndexReady(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));
    }

    @Test
    void ensureIndexReady_updatesMappingInTemplateAndOnPhysicalIndex() throws IOException {
        indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));

        String templateName = IndexNaming.logicalName(INDEX_WRITE_ALIAS);

        BooleanResponse templateExists = client.indices().existsIndexTemplate(
                new ExistsIndexTemplateRequest.Builder().name(templateName).build());
        assertThat(templateExists.value()).isTrue();

        GetIndexTemplateResponse tplResp = client.indices().getIndexTemplate(
                new GetIndexTemplateRequest.Builder().name(templateName).build());
        assertThat(tplResp.indexTemplates()).hasSize(1);
        assertThat(tplResp.indexTemplates().getFirst().indexTemplate().version())
                .isEqualTo(MINOR_VERSION);

        GetMappingResponse mappingResp = client.indices().getMapping(
                new GetMappingRequest.Builder().index(PHYSICAL_INDEX).build());
        IndexMappingRecord indexMappingRecord = mappingResp.result().get(PHYSICAL_INDEX);
        assertThat(indexMappingRecord).isNotNull();
        Map<String, JsonData> meta = indexMappingRecord.mappings().meta();
        assertThat(meta).containsKey(IndexMappingManager.SCHEMA_VERSION_META_KEY);
        String schemaVersion = meta.get(IndexMappingManager.SCHEMA_VERSION_META_KEY)
                .to(String.class, client._transport().jsonpMapper());
        assertThat(schemaVersion).isEqualTo(String.valueOf(MINOR_VERSION));
    }

    @Test
    void indexTemplate_matchesExpectedStructure() throws Exception {
        indexWriter.ensureIndexReady(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));

        String templateName = IndexNaming.logicalName(STRUCTURE_WRITE_ALIAS);
        String actual = prettyJson(httpGet(openSearchUrl + "/_index_template/" + templateName));

        assertThat(actual).isEqualTo("""
                {
                  "index_templates" : [ {
                    "name" : "decrees_v1",
                    "index_template" : {
                      "index_patterns" : [ "decrees_v1-*" ],
                      "template" : {
                        "settings" : {
                          "index" : {
                            "refresh_interval" : "1s",
                            "number_of_shards" : "1",
                            "number_of_replicas" : "1",
                            "plugins" : {
                              "index_state_management" : {
                                "rollover_alias" : "decrees_v1_write"
                              }
                            }
                          }
                        },
                        "mappings" : {
                          "_meta" : {
                            "schema_version" : "3",
                            "custom" : 42,
                            "jeap" : {
                              "collection_fields" : [ "order_id" ]
                            }
                          },
                          "dynamic" : "false",
                          "properties" : {
                            "order_id" : {
                              "type" : "keyword"
                            }
                          }
                        },
                        "aliases" : {
                          "decrees_read" : { }
                        }
                      },
                      "composed_of" : [ ],
                      "version" : 3
                    }
                  } ]
                }""");
    }

    @Test
    void physicalIndexMapping_matchesExpectedStructure() throws Exception {
        indexWriter.ensureIndexReady(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));

        String actual = prettyJson(httpGet(openSearchUrl + "/" + STRUCTURE_INDEX + "/_mapping"));

        assertThat(actual).isEqualTo("""
                {
                  "decrees_v1-000001" : {
                    "mappings" : {
                      "dynamic" : "false",
                      "_meta" : {
                        "schema_version" : "3",
                        "custom" : 42,
                        "jeap" : {
                          "collection_fields" : [ "order_id" ]
                        }
                      },
                      "properties" : {
                        "order_id" : {
                          "type" : "keyword"
                        }
                      }
                    }
                  }
                }""");
    }

    private record OrderData(
            @JsonProperty("order_date") String orderDate,
            @JsonProperty("customer_name") String customerName) {
    }

    @Test
    void upsertSearchItem_writesTypedDataClassWithSnakeCaseFieldNames() throws Exception {
        String docId = "test-snake-case-doc";
        OrderData data = new OrderData("2024-01-01", "Alice");
        SearchItemIndexed<OrderData> searchItem = buildSearchItemWithData(data);

        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, searchItem);

        refreshIndex();
        String raw = httpGet(openSearchUrl + "/" + PHYSICAL_INDEX + "/_doc/" + docId);
        String source = prettyJson(raw);
        assertThat(source)
                .contains("order_date")
                .contains("customer_name")
                .doesNotContain("orderDate")
                .doesNotContain("customerName");
    }

    @Test
    void upsertSearchItem_indexesDocumentInOpenSearch() throws IOException {
        String docId = "test-upsert-doc";

        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());

        refreshIndex();
        assertThat(documentExists(docId)).isTrue();
    }

    @Test
    void upsertSearchItem_updatesExistingDocument() throws IOException {
        String docId = "test-update-doc";

        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());
        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());

        refreshIndex();
        assertThat(documentExists(docId)).isTrue();
    }

    @Test
    void upsertSearchItem_isIdempotent_calledTwiceWithSameIdResultsInSingleDocument() throws IOException {
        String docId = "test-upsert-idempotent-doc";

        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());
        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());

        refreshIndex();
        long count = client.count(c -> c.index(PHYSICAL_INDEX)
                .query(q -> q.ids(i -> i.values(docId)))).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void deleteSearchItem_isIdempotent_calledTwiceOnSameIdDoesNotThrow() throws IOException {
        String docId = "test-delete-idempotent-doc";
        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());
        refreshIndex();

        indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, docId);

        // Second delete on already-absent document must not throw
        assertThatNoException().isThrownBy(() -> indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, docId));
    }

    @Test
    void deleteSearchItem_removesDocumentFromOpenSearch() throws IOException {
        String docId = "test-delete-doc";
        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, docId, buildSearchItem());
        refreshIndex();

        indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, docId);
        refreshIndex();

        assertThat(documentExists(docId)).isFalse();
    }

    @Test
    void ensureIndexReady_logsWarningAndDoesNotThrow_whenIndexHasReadOnlyAllowDeleteBlock() throws Exception {
        String blockedWriteAlias = "payments_v1_write";
        String blockedReadAlias = "payments_read";
        String blockedPhysicalIndex = "payments_v1-000001";

        indexWriter.ensureIndexReady(blockedWriteAlias, blockedReadAlias, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s"));
        try {
            // Simulate disk flood-stage watermark: put index in read-only-allow-delete mode
            httpPut(openSearchUrl + "/" + blockedPhysicalIndex + "/_settings",
                    "{\"index.blocks.read_only_allow_delete\": true}");

            // Call with a newer version to trigger a mapping update — which the block prevents; service logs a warning and continues
            assertThatNoException().isThrownBy(() ->
                    indexWriter.ensureIndexReady(blockedWriteAlias, blockedReadAlias, MINOR_VERSION + 1, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(1, 1, "1s")));
        } finally {
            client.indices().delete(new DeleteIndexRequest.Builder().index(blockedPhysicalIndex).build());
        }
    }

    @Test
    void ensureIndexReady_createsTemplateAndPhysicalIndex_whenNeitherExistsYet() throws Exception {
        String writeAlias = "invoices_v1_write";
        String readAlias = "invoices_read";
        String expectedPhysicalIndex = "invoices_v1-000001";

        try {
            indexWriter.ensureIndexReady(writeAlias, readAlias, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream, new IndexTemplateSettings(2, 0, "5s"));

            GetAliasResponse aliasResponse = client.indices().getAlias(
                    new GetAliasRequest.Builder().index("*").name(writeAlias).build());
            assertThat(aliasResponse.result()).containsKey(expectedPhysicalIndex);
            AliasDefinition aliasDef = aliasResponse.result().get(expectedPhysicalIndex).aliases().get(writeAlias);
            assertThat(aliasDef).isNotNull();
            assertThat(aliasDef.isWriteIndex()).isTrue();

            GetMappingResponse mappingResp = client.indices().getMapping(
                    new GetMappingRequest.Builder().index(expectedPhysicalIndex).build());
            IndexMappingRecord mappingRecord = mappingResp.result().get(expectedPhysicalIndex);
            assertThat(mappingRecord).isNotNull();
            String schemaVersion = mappingRecord.mappings().meta().get(IndexMappingManager.SCHEMA_VERSION_META_KEY)
                    .to(String.class, client._transport().jsonpMapper());
            assertThat(schemaVersion).isEqualTo(String.valueOf(MINOR_VERSION));
        } finally {
            try {
                client.indices().delete(new DeleteIndexRequest.Builder().index(expectedPhysicalIndex).build());
            } catch (Exception ignored) {
                // Best-effort cleanup must not hide the original test failure when the index was not created.
            }
        }
    }

    private boolean documentExists(String docId) throws IOException {
        BooleanResponse response = client.exists(
                new ExistsRequest.Builder().index(PHYSICAL_INDEX).id(docId).build());
        return response.value();
    }

    private void refreshIndex() throws IOException {
        client.indices().refresh(new RefreshRequest.Builder().index(PHYSICAL_INDEX).build());
    }

    private static String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void httpPut(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String prettyJson(String json) throws Exception {
        return new JsonMapper().readTree(json).toPrettyString();
    }

    private static InputStream mappingStream() {
        return new ByteArrayInputStream(MAPPING_JSON.getBytes(StandardCharsets.UTF_8));
    }

    private static SearchItemIndexed<String> buildSearchItem() {
        Origin origin = new Origin("id-1", "1", null, null, Instant.now(), Instant.now(), null);
        SearchItem<String> base = new SearchItem<>(origin, "data");
        SearchItemMetadata meta = SearchItemMetadata.initial(MAJOR_VERSION, MINOR_VERSION);
        return SearchItemIndexed.of(base, meta);
    }

    private static <T> SearchItemIndexed<T> buildSearchItemWithData(T data) {
        Origin origin = new Origin("id-1", "1", null, null, Instant.now(), Instant.now(), null);
        SearchItem<T> base = new SearchItem<>(origin, data);
        SearchItemMetadata meta = SearchItemMetadata.initial(MAJOR_VERSION, MINOR_VERSION);
        return SearchItemIndexed.of(base, meta);
    }
}
