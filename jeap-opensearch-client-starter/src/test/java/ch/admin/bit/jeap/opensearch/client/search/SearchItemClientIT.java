package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAccessDeniedException;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.client.search.SearchItemClientIT.TestConfig;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.testcontainers.OpensearchContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spins up a single-node OpenSearch container and drives {@link SearchItemClient#read}
 * and {@link SearchItemClient#search} through the real HTTP API. Auth is supplied as
 * synthetic {@link Authorization} records so both global-role and BP-role branches run.
 *
 * <p>{@code webEnvironment = NONE} keeps Tomcat out of the slice. Image tag is pinned
 * to a build that needs no initial-password handshake; if it cannot be pulled, try
 * {@code 2.14.0} or {@code 2.18.0}.
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SearchItemClientIT {

    private static final String OPENSEARCH_IMAGE = "opensearchproject/opensearch:2.11.1";

    private static final String INDEX = "inspection_test_v1";

    private static final TestIndexType INDEX_TYPE =
            new TestIndexType("InspectionTest", List.of("inspection_read", "inspection_read_bp"));

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
    private ObjectMapper objectMapper;

    private static volatile boolean indexInitialised;

    /**
     * Lazy index setup so we don't depend on a static @BeforeAll having access to the
     * Spring-managed OpenSearchClient.
     */
    private synchronized void ensureIndex() throws IOException {
        if (indexInitialised) {
            return;
        }
        // Explicit keyword mapping so term-queries match without the .keyword sub-field.
        openSearchClient.indices().create(b -> b
                .index(INDEX)
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
        // Same origin.id in two BPs — exercises the asymmetric uniqueness check between
        // readUnchecked and read(..., auth). Distinct OpenSearch _id lets both coexist.
        indexDocumentWithDocId("dup-1-bp1", "dup-1", "BP1", "dup-own");
        indexDocumentWithDocId("dup-1-bp2", "dup-1", "BP2", "dup-foreign");

        openSearchClient.indices().refresh(r -> r.index(INDEX));
        indexInitialised = true;
    }

    private void indexDocument(String id, String bpId, String label) throws IOException {
        indexDocumentWithDocId(id, id, bpId, label);
    }

    private void indexDocumentWithDocId(String docId, String originId, String bpId, String label) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode originNode = root.putObject("origin");
        originNode.put("id", originId);
        originNode.put("version", "1");
        if (bpId == null) {
            originNode.putNull("bp_id");
        } else {
            originNode.put("bp_id", bpId);
        }
        originNode.putNull("tenant");
        ObjectNode dataNode = root.putObject("data");
        dataNode.put("label", label);

        openSearchClient.index(i -> i
                .index(INDEX)
                .id(docId)
                .document(root)
                .refresh(Refresh.True));
    }

    @Test
    void read_globalRoleAuth_findsDocument_andDeserialisesIt() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.read(INDEX_TYPE, List.of(INDEX), "doc-1", globalAuth);

        assertThat(result).isPresent();
        SearchItemTyped<TestData> item = result.orElseThrow();
        Origin origin = item.origin();
        assertThat(origin.id()).isEqualTo("doc-1");
        assertThat(origin.bpId()).isEqualTo("BP1");
        assertThat(item.data().label()).isEqualTo("alpha");
        assertThat(item.indexType()).isSameAs(INDEX_TYPE);
    }

    @Test
    void read_wrongRoleAuth_throwsIndexTypeAccessDeniedException() throws IOException {
        ensureIndex();
        Authorization wrongAuth = new Authorization(Set.of("not-a-real-role"), Map.of());
        List<String> indices = List.of(INDEX);

        assertThatThrownBy(() ->
                searchItemClient.read(INDEX_TYPE, indices, "doc-1", wrongAuth))
                .isInstanceOf(IndexTypeAccessDeniedException.class);
    }

    @Test
    void read_nonExistentId_returnsEmpty() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.read(INDEX_TYPE, List.of(INDEX), "does-not-exist", globalAuth);

        assertThat(result).isEmpty();
    }

    @Test
    void search_matchAll_withGlobalRole_returnsAllDocuments() throws IOException {
        ensureIndex();
        Authorization globalAuth = new Authorization(Set.of("inspection_read"), Map.of());

        List<SearchItemTyped<TestData>> result = searchItemClient.search(
                INDEX_TYPE, List.of(INDEX),
                Query.of(q -> q.matchAll(m -> m)),
                globalAuth);

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactlyInAnyOrder("doc-1", "doc-2", "doc-3", "dup-1", "dup-1");
    }

    @Test
    void search_matchAll_withBp1OnlyRole_returnsOnlyBp1Documents() throws IOException {
        ensureIndex();
        Authorization bp1Auth = new Authorization(
                Set.of(),
                Map.of("BP1", Set.of("inspection_read_bp")));

        List<SearchItemTyped<TestData>> result = searchItemClient.search(
                INDEX_TYPE, List.of(INDEX),
                Query.of(q -> q.matchAll(m -> m)),
                bp1Auth);

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactlyInAnyOrder("doc-1", "dup-1");
    }

    @Test
    void searchUnchecked_returnsAllDocuments_withoutAuthChecks() throws IOException {
        ensureIndex();

        List<SearchItemTyped<TestData>> result = searchItemClient.searchUnchecked(
                INDEX_TYPE, List.of(INDEX),
                Query.of(q -> q.matchAll(m -> m)));

        assertThat(result)
                .extracting(item -> item.origin().id())
                .containsExactlyInAnyOrder("doc-1", "doc-2", "doc-3", "dup-1", "dup-1");
    }

    @Test
    void readUnchecked_returnsDocument_withoutAuthChecks() throws IOException {
        ensureIndex();

        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.readUnchecked(INDEX_TYPE, List.of(INDEX), "doc-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().origin().id()).isEqualTo("doc-1");
    }

    @Test
    void read_bp1OnlyRole_foreignBpDocument_returnsEmpty() throws IOException {
        // A non-authorised hit is silently filtered out — read returns empty, doesn't throw.
        ensureIndex();
        Authorization bp1Auth = new Authorization(
                Set.of(),
                Map.of("BP1", Set.of("inspection_read_bp")));

        // doc-2 belongs to BP2 → not visible to the BP1-only user.
        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.read(INDEX_TYPE, List.of(INDEX), "doc-2", bp1Auth);

        assertThat(result).isEmpty();
    }

    @Test
    void read_bp1OnlyRole_ownBpDocument_returnsItem() throws IOException {
        ensureIndex();
        Authorization bp1Auth = new Authorization(
                Set.of(),
                Map.of("BP1", Set.of("inspection_read_bp")));

        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.read(INDEX_TYPE, List.of(INDEX), "doc-1", bp1Auth);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().origin().bpId()).isEqualTo("BP1");
    }

    @Test
    void read_bp1OnlyAuth_duplicateIdInForeignBp_returnsOnlyOwnItem_noException() throws IOException {
        // dup-1 exists in both BP1 and BP2; auth-scope uniqueness hides the foreign duplicate.
        ensureIndex();
        Authorization bp1Auth = new Authorization(
                Set.of(),
                Map.of("BP1", Set.of("inspection_read_bp")));

        Optional<SearchItemTyped<TestData>> result =
                searchItemClient.read(INDEX_TYPE, List.of(INDEX), "dup-1", bp1Auth);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().origin().bpId()).isEqualTo("BP1");
        assertThat(result.orElseThrow().data().label()).isEqualTo("dup-own");
    }

    @Test
    void readUnchecked_duplicateIdAcrossBps_throwsMultipleSearchItemsFoundException() throws IOException {
        // readUnchecked enforces global uniqueness — admin/reporting paths see the inconsistency.
        ensureIndex();
        List<String> indices = List.of(INDEX);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                searchItemClient.readUnchecked(INDEX_TYPE, indices, "dup-1"))
                .isInstanceOf(MultipleSearchItemsFoundException.class);
    }

    @Test
    void read_globalAuth_duplicateIdAcrossBps_throwsMultipleSearchItemsFoundException() throws IOException {
        // Global role → no BP pre-filter → post-filter keeps both hits → exception surfaces.
        ensureIndex();
        Authorization globalAuth = new Authorization(
                Set.of("inspection_read"), Map.of());
        List<String> indices = List.of(INDEX);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                searchItemClient.read(INDEX_TYPE, indices, "dup-1", globalAuth))
                .isInstanceOf(MultipleSearchItemsFoundException.class);
    }

    public record TestData(String label) {
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
        @Override public String description() { return "Integration-test index type"; }
        @Override public String documentationUrl() { return "https://example.test/doc"; }
        @Override public List<String> roles() { return roles; }
        @Override public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }
    }

    @SpringBootApplication(
            // Disable component scan — only the imported auto-configurations should contribute beans.
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
