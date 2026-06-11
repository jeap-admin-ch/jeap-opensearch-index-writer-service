package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAccessDeniedException;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemView;
import ch.admin.bit.jeap.opensearch.client.search.SearchItemClientIT.TestConfig;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.testcontainers.OpensearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spins up a single-node OpenSearch container and drives {@link SearchItemClient}
 * multi-version search methods through the real HTTP API. Auth is supplied as
 * synthetic {@link Authorization} records so both global-role and BP-role branches run.
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SearchItemClientIT {

    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:2.11.1";

    private static final TestIndexType INDEX_TYPE =
            new TestIndexType("InspectionTest", List.of("inspection_read", "inspection_read_bp"));

    private static final TestIndexTypeV2 INDEX_TYPE_V2 =
            new TestIndexTypeV2("InspectionTest", List.of("inspection_read", "inspection_read_bp"));

    @Container
    static final OpensearchContainer<?> opensearch = new OpensearchContainer<>(OPENSEARCH_IMAGE);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("jeap.opensearch.client.connection.uri", opensearch::getHttpHostAddress);
    }

    @Autowired
    private SearchItemClient searchItemClient;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private JsonMapper jsonMapper;

    private static volatile boolean indexInitialised;
    private static volatile boolean multiVersionIndexInitialised;

    /**
     * Lazy index setup so we don't depend on a static @BeforeAll having access to the
     * Spring-managed OpenSearchClient.
     */
    private synchronized void ensureIndex() throws IOException {
        if (indexInitialised) {
            return;
        }
        openSearchClient.indices().create(b -> b
                .index(INDEX_TYPE.indexReadAlias())
                .mappings(m -> m
                        .properties("origin", p -> p.object(o -> o
                                .properties("id", pp -> pp.keyword(k -> k))
                                .properties("bp_id", pp -> pp.keyword(k -> k))
                                .properties("tenant", pp -> pp.keyword(k -> k))))
                        .properties("data", p -> p.object(o -> o
                                .properties("label", pp -> pp.keyword(k -> k))))));

        indexDocument("doc-1", "BP1", "alpha");
        indexDocument("doc-2", "BP2", "beta");
        indexDocument("doc-3", null, "gamma");

        openSearchClient.indices().refresh(r -> r.index(INDEX_TYPE.indexReadAlias()));
        indexInitialised = true;
    }

    private void indexDocument(String id, String bpId, String label) throws IOException {
        ObjectNode root = jsonMapper.createObjectNode();
        ObjectNode originNode = root.putObject("origin");
        originNode.put("id", id);
        originNode.put("version", "1");
        if (bpId == null) {
            originNode.putNull("bp_id");
        } else {
            originNode.put("bp_id", bpId);
        }
        originNode.putNull("tenant");
        ObjectNode dataNode = root.putObject("data");
        dataNode.put("label", label);
        root.putObject("search_item").put("major_version", 1);

        openSearchClient.index(i -> i
                .index(INDEX_TYPE.indexReadAlias())
                .id(id)
                .document(root)
                .refresh(Refresh.True));
    }

    @Test
    void searchMultiVersion_globalRoleAuth_findsDocumentAndDeserialisesIt() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        List<SearchItemView> result = searchItemClient.searchMultiVersion(
                List.of(INDEX_TYPE),
                Query.of(q -> q.term(t -> t.field("origin.id").value(FieldValue.of("doc-1")))),
                globalAuth);

        assertThat(result).hasSize(1);
        SearchItemView item = result.getFirst();
        Origin origin = item.origin();
        assertThat(origin.id()).isEqualTo("doc-1");
        assertThat(origin.bpId()).isEqualTo("BP1");
        assertThat(((TestData) item.data()).label()).isEqualTo("alpha");
        assertThat(item.indexType()).isSameAs(INDEX_TYPE);
    }

    @Test
    void searchMultiVersion_wrongRoleAuth_throwsIndexTypeAccessDeniedException() throws IOException {
        ensureIndex();
        Authorization wrongAuth = new Authorization(Set.of("not-a-real-role"), Map.of());

        assertThatThrownBy(() ->
                searchItemClient.searchMultiVersion(
                        List.of(INDEX_TYPE),
                        Query.of(q -> q.matchAll(m -> m)),
                        wrongAuth))
                .isInstanceOf(IndexTypeAccessDeniedException.class);
    }

    @Test
    void searchMultiVersion_nonExistentId_returnsEmpty() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        List<SearchItemView> result = searchItemClient.searchMultiVersion(
                List.of(INDEX_TYPE),
                Query.of(q -> q.term(t -> t.field("origin.id").value(FieldValue.of("does-not-exist")))),
                globalAuth);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMultiVersion_matchAll_withGlobalRole_returnsAllDocuments() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        List<SearchItemView> result = searchItemClient.searchMultiVersion(
                List.of(INDEX_TYPE),
                Query.of(q -> q.matchAll(m -> m)),
                globalAuth);

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactlyInAnyOrder("doc-1", "doc-2", "doc-3");
    }

    @Test
    void searchMultiVersion_matchAll_withBp1OnlyRole_returnsOnlyBp1Documents() throws IOException {
        ensureIndex();
        Authorization bp1Auth = new Authorization(
                Set.of(),
                Map.of("BP1", Set.of("inspection_read_bp")));

        List<SearchItemView> result = searchItemClient.searchMultiVersion(
                List.of(INDEX_TYPE),
                Query.of(q -> q.matchAll(m -> m)),
                bp1Auth);

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactly("doc-1");
    }

    @Test
    void searchMultiVersionUnchecked_returnsAllDocuments_withoutAuthChecks() throws IOException {
        ensureIndex();

        List<SearchItemView> result = searchItemClient.searchMultiVersionUnchecked(
                List.of(INDEX_TYPE),
                Query.of(q -> q.matchAll(m -> m)));

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactlyInAnyOrder("doc-1", "doc-2", "doc-3");
    }

    // ── multi-version dispatch (v1 + v2 in the same read alias) ──────────────

    /**
     * Lazy setup for the multi-version index: creates the index and indexes one V1 and one V2
     * document under the shared read-alias derived from both TestIndexType and TestIndexTypeV2.
     */
    private synchronized void ensureMultiVersionIndex() throws IOException {
        if (multiVersionIndexInitialised) {
            return;
        }
        String alias = INDEX_TYPE_V2.indexReadAlias() + "_mv";
        openSearchClient.indices().create(b -> b
                .index(alias)
                .mappings(m -> m
                        .properties("origin", p -> p.object(o -> o
                                .properties("id", pp -> pp.keyword(k -> k))
                                .properties("bp_id", pp -> pp.keyword(k -> k))
                                .properties("tenant", pp -> pp.keyword(k -> k))))
                        .properties("data", p -> p.object(o -> o
                                .properties("label", pp -> pp.keyword(k -> k))
                                .properties("name", pp -> pp.keyword(k -> k))))
                        .properties("search_item", p -> p.object(o -> o
                                .properties("major_version", pp -> pp.integer(i -> i))))));

        indexVersionedDocument(alias, "mv-doc-v1", null, "v1-label", null, 1);
        indexVersionedDocument(alias, "mv-doc-v2", null, null, "v2-name", 2);
        indexVersionedDocument(alias, "mv-doc-unknown", null, "x", null, 99);

        openSearchClient.indices().refresh(r -> r.index(alias));
        multiVersionIndexInitialised = true;
    }

    private void indexVersionedDocument(String index, String id, String bpId,
                                        String label, String name, int majorVersion) throws IOException {
        ObjectNode root = jsonMapper.createObjectNode();
        ObjectNode originNode = root.putObject("origin");
        originNode.put("id", id);
        originNode.put("version", "1");
        if (bpId == null) originNode.putNull("bp_id"); else originNode.put("bp_id", bpId);
        originNode.putNull("tenant");
        ObjectNode dataNode = root.putObject("data");
        if (label != null) dataNode.put("label", label);
        if (name != null) dataNode.put("name", name);
        root.putObject("search_item").put("major_version", majorVersion);
        openSearchClient.index(i -> i.index(index).id(id).document(root).refresh(Refresh.True));
    }

    /** Returns a version of INDEX_TYPE_V2 that uses the multi-version alias. */
    private static TestIndexTypeWithAlias mvIndexTypeV1() {
        return new TestIndexTypeWithAlias(INDEX_TYPE, INDEX_TYPE_V2.indexReadAlias() + "_mv");
    }

    private static TestIndexTypeWithAlias mvIndexTypeV2() {
        return new TestIndexTypeWithAlias(INDEX_TYPE_V2, INDEX_TYPE_V2.indexReadAlias() + "_mv");
    }

    @Test
    void searchMultiVersion_v1AndV2Docs_dispatchedToCorrectDataClass() throws IOException {
        ensureMultiVersionIndex();
        Authorization auth = new Authorization(Set.of("inspection_read"), Map.of());

        List<SearchItemView> result = searchItemClient.searchMultiVersion(
                List.of(mvIndexTypeV1(), mvIndexTypeV2()),
                Query.of(q -> q.terms(t -> t.field("origin.id")
                        .terms(tf -> tf.value(List.of(
                                FieldValue.of("mv-doc-v1"),
                                FieldValue.of("mv-doc-v2")))))),
                auth);

        assertThat(result).hasSize(2);
        SearchItemView v1Item = result.stream()
                .filter(it -> "mv-doc-v1".equals(it.origin().id())).findFirst().orElseThrow();
        SearchItemView v2Item = result.stream()
                .filter(it -> "mv-doc-v2".equals(it.origin().id())).findFirst().orElseThrow();
        assertThat(v1Item.data()).isInstanceOf(TestData.class);
        assertThat(((TestData) v1Item.data()).label()).isEqualTo("v1-label");
        assertThat(v2Item.data()).isInstanceOf(TestDataV2.class);
        assertThat(((TestDataV2) v2Item.data()).name()).isEqualTo("v2-name");
    }

    @Test
    void searchMultiVersion_unknownMajorVersion_throwsSearchItemClientException() throws IOException {
        ensureMultiVersionIndex();
        Authorization auth = new Authorization(Set.of("inspection_read"), Map.of());

        assertThatThrownBy(() ->
                searchItemClient.searchMultiVersion(
                        List.of(mvIndexTypeV1(), mvIndexTypeV2()),
                        Query.of(q -> q.term(t -> t.field("origin.id").value(FieldValue.of("mv-doc-unknown")))),
                        auth))
                .isInstanceOf(SearchItemClientException.class)
                .hasMessageContaining("99");
    }

    public record TestData(String label) {
    }

    public record TestDataV2(String name) {
    }

    static final class TestIndexType implements IndexType<TestData> {
        private final String originType;
        private final List<String> roles;

        TestIndexType(String originType, List<String> roles) {
            this.originType = originType;
            this.roles = List.copyOf(roles);
        }

        @Override public Class<TestData> dataClass() { return TestData.class; }
        @Override public String system() { return "jme"; }
        @Override public String originType() { return originType; }
        @Override public int majorVersion() { return 1; }
        @Override public int minorVersion() { return 0; }
        @Override public String description() { return "Integration-test index type v1"; }
        @Override public String documentationUrl() { return "https://example.test/doc"; }
        @Override public List<String> roles() { return roles; }
        @Override public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }
    }

    static final class TestIndexTypeV2 implements IndexType<TestDataV2> {
        private final String originType;
        private final List<String> roles;

        TestIndexTypeV2(String originType, List<String> roles) {
            this.originType = originType;
            this.roles = List.copyOf(roles);
        }

        @Override public Class<TestDataV2> dataClass() { return TestDataV2.class; }
        @Override public String system() { return "jme"; }
        @Override public String originType() { return originType; }
        @Override public int majorVersion() { return 2; }
        @Override public int minorVersion() { return 0; }
        @Override public String description() { return "Integration-test index type v2"; }
        @Override public String documentationUrl() { return "https://example.test/doc"; }
        @Override public List<String> roles() { return roles; }
        @Override public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }
    }

    /** Wraps an existing IndexType but overrides the indexReadAlias for test isolation. */
    static final class TestIndexTypeWithAlias<T> implements IndexType<T> {
        private final IndexType<T> delegate;
        private final String alias;

        TestIndexTypeWithAlias(IndexType<T> delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override public Class<T> dataClass() { return delegate.dataClass(); }
        @Override public String system() { return delegate.system(); }
        @Override public String originType() { return delegate.originType(); }
        @Override public int majorVersion() { return delegate.majorVersion(); }
        @Override public int minorVersion() { return delegate.minorVersion(); }
        @Override public String description() { return delegate.description(); }
        @Override public String documentationUrl() { return delegate.documentationUrl(); }
        @Override public List<String> roles() { return delegate.roles(); }
        @Override public Supplier<InputStream> mappingDefinition() { return delegate.mappingDefinition(); }
        @Override public String indexReadAlias() { return alias; }
    }

    @SpringBootApplication(
            scanBasePackages = "ch.admin.bit.jeap.opensearch.client.search.empty"
    )
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class,
            ch.admin.bit.jeap.opensearch.client.config.OpenSearchClientConfiguration.class,
            ch.admin.bit.jeap.opensearch.client.auth.AuthorizationAutoConfiguration.class,
            SearchAutoConfiguration.class
    })
    public static class TestConfig {
    }

}
