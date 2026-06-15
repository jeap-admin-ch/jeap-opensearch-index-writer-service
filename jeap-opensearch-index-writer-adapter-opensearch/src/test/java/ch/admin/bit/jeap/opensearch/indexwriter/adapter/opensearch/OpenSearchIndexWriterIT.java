package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
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
import static tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS;

@Testcontainers
class OpenSearchIndexWriterIT {

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";
    private static final String INDEX_READ_ALIAS = "orders_read";
    private static final String PHYSICAL_INDEX = "orders_v1-000001";
    private static final String STRUCTURE_WRITE_ALIAS = "orders_v1_structure_write";
    private static final String STRUCTURE_READ_ALIAS = "orders_v1_structure_read";
    private static final String STRUCTURE_INDEX = "orders_v1_structure-000001";
    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 3;

    private static final String MAPPING_JSON = """
            {
              "mappings": {
                "dynamic": false,
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
        indexWriter = new OpenSearchIndexWriter(client, dataFieldValidator, indexTemplateManager, physicalIndexManager, indexMappingManager);

        createIaCTemplate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS);
        client.indices().create(new CreateIndexRequest.Builder().index(PHYSICAL_INDEX).build());
        client.indices().putAlias(new PutAliasRequest.Builder()
                .index(PHYSICAL_INDEX)
                .name(INDEX_WRITE_ALIAS)
                .build());
        // Apply the mapping immediately so that dynamic: false is in effect before any document is written.
        // Without this, OpenSearch auto-maps unknown fields (e.g. data as text), causing type conflicts.
        indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream);

        createIaCTemplate(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS);
        client.indices().create(new CreateIndexRequest.Builder().index(STRUCTURE_INDEX).build());
        client.indices().putAlias(new PutAliasRequest.Builder()
                .index(STRUCTURE_INDEX)
                .name(STRUCTURE_WRITE_ALIAS)
                .build());
    }

    @Test
    void ensureIndexReady_updatesMappingInTemplateAndOnPhysicalIndex() throws IOException {
        indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream);

        String templateName = IndexTemplateManager.templateName(INDEX_WRITE_ALIAS);

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
        indexWriter.ensureIndexReady(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream);

        String templateName = IndexTemplateManager.templateName(STRUCTURE_WRITE_ALIAS);
        String actual = prettyJson(httpGet(openSearchUrl + "/_index_template/" + templateName));

        assertThat(actual).isEqualTo("""
                {
                  "index_templates" : [ {
                    "name" : "orders_v1_structure",
                    "index_template" : {
                      "index_patterns" : [ "orders_v1_structure-*" ],
                      "template" : {
                        "settings" : {
                          "index" : {
                            "plugins" : {
                              "index_state_management" : {
                                "rollover_alias" : "orders_v1_structure_write"
                              }
                            }
                          }
                        },
                        "mappings" : {
                          "_meta" : {
                            "schema_version" : "3"
                          },
                          "dynamic" : "false",
                          "properties" : {
                            "order_id" : {
                              "type" : "keyword"
                            }
                          }
                        },
                        "aliases" : {
                          "orders_v1_structure_read" : { }
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
        indexWriter.ensureIndexReady(STRUCTURE_WRITE_ALIAS, STRUCTURE_READ_ALIAS, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream);

        String actual = prettyJson(httpGet(openSearchUrl + "/" + STRUCTURE_INDEX + "/_mapping"));

        assertThat(actual).isEqualTo("""
                {
                  "orders_v1_structure-000001" : {
                    "mappings" : {
                      "dynamic" : "false",
                      "_meta" : {
                        "schema_version" : "3"
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
        String blockedPhysicalIndex = "orders_v1-blocked-000001";
        String blockedWriteAlias = "orders_v1_blocked_write";
        String blockedReadAlias = "orders_v1_blocked_read";

        createIaCTemplate(blockedWriteAlias, blockedReadAlias);
        client.indices().create(new CreateIndexRequest.Builder().index(blockedPhysicalIndex).build());
        client.indices().putAlias(new PutAliasRequest.Builder()
                .index(blockedPhysicalIndex)
                .name(blockedWriteAlias)
                .build());
        try {
            // Simulate disk flood-stage watermark: put index in read-only-allow-delete mode
            httpPut(openSearchUrl + "/" + blockedPhysicalIndex + "/_settings",
                    "{\"index.blocks.read_only_allow_delete\": true}");

            assertThatNoException().isThrownBy(() ->
                    indexWriter.ensureIndexReady(blockedWriteAlias, blockedReadAlias, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream));
        } finally {
            client.indices().delete(new DeleteIndexRequest.Builder().index(blockedPhysicalIndex).build());
        }
    }

    @Test
    void ensureIndexReady_createsPhysicalIndex_whenWriteAliasDoesNotExist() throws Exception {
        String writeAlias = "orders_v1_autocreate_write";
        String readAlias = "orders_v1_autocreate_read";
        String expectedPhysicalIndex = "orders_v1_autocreate-000001";

        createIaCTemplate(writeAlias, readAlias);
        try {
            indexWriter.ensureIndexReady(writeAlias, readAlias, MINOR_VERSION, OpenSearchIndexWriterIT::mappingStream);

            GetAliasResponse aliasResponse = client.indices().getAlias(
                    new GetAliasRequest.Builder().index("*").name(writeAlias).build());
            assertThat(aliasResponse.result()).containsKey(expectedPhysicalIndex);
            AliasDefinition aliasDef = aliasResponse.result().get(expectedPhysicalIndex).aliases().get(writeAlias);
            assertThat(aliasDef).isNotNull();
            assertThat(aliasDef.isWriteIndex()).isTrue();

            GetMappingResponse mappingResp = client.indices().getMapping(
                    new GetMappingRequest.Builder().index(expectedPhysicalIndex).build());
            IndexMappingRecord record = mappingResp.result().get(expectedPhysicalIndex);
            assertThat(record).isNotNull();
            String schemaVersion = record.mappings().meta().get(IndexMappingManager.SCHEMA_VERSION_META_KEY)
                    .to(String.class, client._transport().jsonpMapper());
            assertThat(schemaVersion).isEqualTo(String.valueOf(MINOR_VERSION));
        } finally {
            try {
                client.indices().delete(new DeleteIndexRequest.Builder().index(expectedPhysicalIndex).build());
            } catch (Exception ignored) {}
        }
    }

    private static void createIaCTemplate(String writeAlias, String readAlias) throws IOException {
        // Only the read alias goes in the template — write alias is managed exclusively by ISM rollover.
        // Having is_write_index: true in the template causes a "duplicated alias" error when ISM creates
        // the next rollover index (the template would apply the alias before ISM can atomically swap it).
        client.indices().putIndexTemplate(new PutIndexTemplateRequest.Builder()
                .name(IndexTemplateManager.templateName(writeAlias))
                .indexPatterns(IndexTemplateManager.indexPattern(writeAlias))
                .template(t -> t
                        .aliases(Map.of(readAlias, new Alias.Builder().build()))
                        .settings(s -> s.customSettings(
                                "plugins.index_state_management.rollover_alias", JsonData.of(writeAlias))))
                .version(0L)
                .build());
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
