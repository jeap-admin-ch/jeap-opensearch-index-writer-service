package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.*;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemView;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestData;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestDataV2;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestIndexType;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestIndexTypeV2;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchItemClientTest {

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private SearchItemAuthorization searchItemAuthorization;

    @Mock
    private UserSearchItemAuthorization userSearchItemAuthorization;

    @Mock
    private IndexTypeAuthorization mockIndexTypeAuthorization;

    private final JsonMapper jsonMapper = new JsonMapper();
    private final IndexTypeAuthorization indexTypeAuthorization = new IndexTypeAuthorization();

    private TestIndexType indexType;
    private TestIndexTypeV2 indexTypeV2;
    private Authorization globalAuth;

    @BeforeEach
    void setUp() {
        indexType = new TestIndexType(List.of("inspection_read"));
        indexTypeV2 = new TestIndexTypeV2(List.of("inspection_read"));
        globalAuth = new Authorization(Set.of("inspection_read"), Map.of());
    }

    private SearchItemClient newClient() {
        return new SearchItemClient(
                openSearchClient,
                jsonMapper,
                indexTypeAuthorization,
                searchItemAuthorization,
                userSearchItemAuthorization);
    }

    private void whenSearchReturnsHits(List<Hit<JsonNode>> hits) throws IOException {
        SearchResponse<JsonNode> response = mockResponseWithHits(hits);
        when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<JsonNode> mockResponseWithHits(List<Hit<JsonNode>> hits) {
        SearchResponse<JsonNode> response = (SearchResponse<JsonNode>) org.mockito.Mockito.mock(SearchResponse.class);
        HitsMetadata<JsonNode> hitsMetadata = (HitsMetadata<JsonNode>) org.mockito.Mockito.mock(HitsMetadata.class);
        lenient().when(response.hits()).thenReturn(hitsMetadata);
        lenient().when(hitsMetadata.hits()).thenReturn(hits);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Hit<JsonNode> mockHit(String id, JsonNode source) {
        Hit<JsonNode> hit = (Hit<JsonNode>) org.mockito.Mockito.mock(Hit.class);
        lenient().when(hit.id()).thenReturn(id);
        lenient().when(hit.source()).thenReturn(source);
        return hit;
    }

    private SearchRequest captureSearchRequest() throws IOException {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(openSearchClient).search(captor.capture(), eq(JsonNode.class));
        return captor.getValue();
    }

    @SuppressWarnings("SameParameterValue")
    private Authorization bpOnlyAuth(String bpId) {
        return new Authorization(Set.of(), Map.of(bpId, Set.of("inspection_read")));
    }

    @Nested
    class ExceptionWrapping {

        @Test
        void ioException_wrappedInSearchItemClientException_withCause() throws IOException {
            SearchItemClient sut = newClient();
            IOException ioe = new IOException("transport failed");
            when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                    .thenThrow(ioe);
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(types, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex -> {
                        assertThat(ex.getCause()).isSameAs(ioe);
                        assertThat(ex.getMessage())
                                .contains(indexTypeV2.getClass().getSimpleName());
                    });
        }

        @Test
        void openSearchException_wrappedInSearchItemClientException() throws IOException {
            SearchItemClient sut = newClient();
            OpenSearchException ose = org.mockito.Mockito.mock(OpenSearchException.class);
            ErrorResponse errorResponse = org.mockito.Mockito.mock(ErrorResponse.class);
            ErrorCause errorCause = org.mockito.Mockito.mock(ErrorCause.class);
            when(ose.status()).thenReturn(404);
            when(ose.response()).thenReturn(errorResponse);
            when(errorResponse.error()).thenReturn(errorCause);
            when(errorCause.type()).thenReturn("index_not_found_exception");
            when(errorCause.reason()).thenReturn("no such index");
            when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                    .thenThrow(ose);
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(types, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex -> {
                        assertThat(ex.getCause()).isSameAs(ose);
                        assertThat(ex.getMessage()).contains("404").contains("index_not_found_exception");
                    });
        }

        @Test
        void deserializationError_wrappedInSearchItemClientException() throws IOException {
            SearchItemClient sut = newClient();
            // Build a doc with valid search_item.major_version=1 but bad data field.
            ObjectNode root = jsonMapper.createObjectNode();
            ObjectNode originNode = root.putObject("origin");
            originNode.put("id", "id-1");
            originNode.put("version", "1");
            originNode.putNull("bp_id");
            originNode.putNull("tenant");
            root.put("data", "not-an-object");
            root.putObject("search_item").put("major_version", 1);
            whenSearchReturnsHits(List.of(mockHit("d1", root)));
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(types, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex ->
                            assertThat(ex.getCause())
                                    .isInstanceOfAny(MismatchedInputException.class, IllegalArgumentException.class));
        }

        @Test
        void exceptionWrappingAlsoAppliesToAuthorizedSearches() throws IOException {
            SearchItemClient sut = newClient();
            when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                    .thenThrow(new IOException("boom"));
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchMultiVersion(types, query, globalAuth))
                    .isInstanceOf(SearchItemClientException.class);
        }
    }

    // ============================================================================
    // multi-version typed
    // ============================================================================

    @Nested
    class SearchMultiVersionUncheckedTyped {

        @Test
        void deserialisesV1AndV2Hits_withCorrectIndexType() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-1", "BP1", "label", "alpha", 1);
            JsonNode s2 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-2", "BP2", "name", "beta", 2);
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));

            List<SearchItemView> result = sut.searchMultiVersionUnchecked(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).indexType()).isSameAs(indexType);
            assertThat(result.get(0).data()).isInstanceOf(TestData.class);
            assertThat(((TestData) result.get(0).data()).label()).isEqualTo("alpha");
            assertThat(result.get(1).indexType()).isSameAs(indexTypeV2);
            assertThat(result.get(1).data()).isInstanceOf(TestDataV2.class);
            assertThat(((TestDataV2) result.get(1).data()).name()).isEqualTo("beta");
        }

        @Test
        void docMissingMajorVersion_throwsSearchItemClientException() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJson(jsonMapper, "id-1", "BP1", "alpha");
            JsonNode s2 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-2", "BP2", "label", "beta", 1);
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(
                            List.of(indexType, indexTypeV2),
                            Query.of(q -> q.matchAll(m -> m))))
                    .isInstanceOf(SearchItemClientException.class)
                    .hasMessageContaining("d1")
                    .hasMessageContaining("major_version");
        }

        @Test
        void docUnknownMajorVersion_throwsSearchItemClientException() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-1", "BP1", "label", "alpha", 1);
            JsonNode sUnknown = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-99", "BP1", "label", "orphan", 99);
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d99", sUnknown)));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(
                            List.of(indexType, indexTypeV2),
                            Query.of(q -> q.matchAll(m -> m))))
                    .isInstanceOf(SearchItemClientException.class)
                    .hasMessageContaining("d99")
                    .hasMessageContaining("99");
        }

        @Test
        void nullSource_isSilentlyDropped() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-1", "BP1", "label", "alpha", 1);
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", null)));

            List<SearchItemView> result = sut.searchMultiVersionUnchecked(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)));

            assertThat(result).hasSize(1);
        }

        @Test
        void derivedIndices_matchIndexReadAliasOfIndexTypes() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.searchMultiVersionUnchecked(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)));

            // v1 and v2 share the same originType → same indexReadAlias; distinct() yields one entry
            assertThat(captureSearchRequest().index())
                    .containsExactly(indexType.indexReadAlias());
        }

        @Test
        void allTwoOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.searchMultiVersionUnchecked(types, q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchMultiVersionUnchecked(types, q)).isEmpty();
        }

        @Test
        void differentSystem_throwsIllegalArgument() {
            SearchItemClient sut = newClient();
            TestIndexTypeV2 otherSystem = new TestIndexTypeV2(List.of("inspection_read"), "other");

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(
                            List.of(indexType, otherSystem),
                            Query.of(q -> q.matchAll(m -> m))))
                    .isInstanceOf(SearchItemClientException.class)
                    .hasMessageContaining("system");
        }

        @Test
        void differentRoles_succeeds_usesLatestVersionRoles() throws IOException {
            SearchItemClient sut = newClient();
            // V2 has different roles — this is now allowed; latest version's roles are used for auth
            TestIndexTypeV2 differentRolesV2 = new TestIndexTypeV2(List.of("other_role"));
            whenSearchReturnsHits(List.of());

            assertThat(sut.searchMultiVersionUnchecked(
                    List.of(indexType, differentRolesV2),
                    Query.of(q -> q.matchAll(m -> m)))).isEmpty();
        }

        @Test
        void duplicateMajorVersion_throwsIllegalArgument() {
            SearchItemClient sut = newClient();
            TestIndexType anotherV1 = new TestIndexType(List.of("inspection_read"));

            assertThatThrownBy(() ->
                    sut.searchMultiVersionUnchecked(
                            List.of(indexType, anotherV1),
                            Query.of(q -> q.matchAll(m -> m))))
                    .isInstanceOf(SearchItemClientException.class)
                    .hasMessageContaining("Duplicate major version");
        }
    }

    @Nested
    class SearchMultiVersionWithAuthTyped {

        @Test
        @SuppressWarnings("DataFlowIssue")
        void nullAuth_throwsNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);

            assertThatThrownBy(() ->
                    sut.searchMultiVersion(types,
                            Query.of(q -> q.matchAll(m -> m)), null))
                    .isInstanceOfSatisfying(IndexTypeAccessDeniedException.class,
                            ex -> assertThat(ex.getMessage()).contains("no authorization"));

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void globalUserrole_noBpPreFilterApplied_queryPassedThroughUnchanged() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth), any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            Query original = Query.of(q -> q.matchAll(m -> m));

            sut.searchMultiVersion(List.of(indexType, indexTypeV2),
                    original, globalAuth);

            SearchRequest req = captureSearchRequest();
            assertThat(req.query()).isSameAs(original);
        }

        @Test
        void postFilter_dropsUnauthorisedItems() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-1", "BP1", "label", "alpha", 1);
            JsonNode s2 = SearchTestData.sourceJsonWithMajorVersion(
                    jsonMapper, "id-2", "BP2", "label", "beta", 1);
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth), any()))
                    .thenAnswer(inv -> {
                        List<SearchItemView> input = inv.getArgument(0);
                        return input.stream().filter(it -> !"id-2".equals(it.origin().id())).toList();
                    });

            List<SearchItemView> result = sut.searchMultiVersion(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)), globalAuth);

            assertThat(result).extracting(it -> it.origin().id()).containsExactly("id-1");
        }

        @Test
        void allTwoOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), any(), any())).thenReturn(List.of());
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.searchMultiVersion(types, q, b -> b.size(5), globalAuth)).isEmpty();
            assertThat(sut.searchMultiVersion(types, q, globalAuth)).isEmpty();
        }
    }

    @Nested
    class SearchMultiVersionWithUserAuthTyped {

        @Test
        void providerNull_routesIntoNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(null);

            assertThatThrownBy(() ->
                    sut.searchMultiVersionWithUserAuth(
                            List.of(indexType, indexTypeV2),
                            Query.of(q -> q.matchAll(m -> m))))
                    .isInstanceOf(IndexTypeAccessDeniedException.class);

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void providerReturnsAuth_isThreadedThroughToPostFilter() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth), any())).thenReturn(List.of());

            sut.searchMultiVersionWithUserAuth(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)));

            verify(searchItemAuthorization).filterByAuthorization(any(), eq(globalAuth), any());
        }

        @Test
        void allTwoOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), any(), any())).thenReturn(List.of());
            List<IndexType<?>> types = List.of(indexType, indexTypeV2);
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.searchMultiVersionWithUserAuth(types, q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchMultiVersionWithUserAuth(types, q)).isEmpty();
        }
    }

    private SearchItemClient newClientWithPermissiveIndexTypeAuth() {
        return new SearchItemClient(
                openSearchClient,
                jsonMapper,
                mockIndexTypeAuthorization,    // no-op checkAccess
                new SearchItemAuthorization(), // real post-filter
                userSearchItemAuthorization);
    }

    @Nested
    class BpPreFilter {

        @Test
        void bpOnlyAuth_appliesBpPreFilter_wrappingQueryInBool() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), any(), any())).thenReturn(List.of());
            Authorization bp1Auth = bpOnlyAuth("BP1");

            sut.searchMultiVersion(
                    List.of(indexType, indexTypeV2),
                    Query.of(q -> q.matchAll(m -> m)),
                    bp1Auth);

            SearchRequest req = captureSearchRequest();
            assertThat(req.query().isBool()).isTrue();
            assertThat(req.query().bool().filter()).hasSize(1);
            Query filter = req.query().bool().filter().getFirst();
            assertThat(filter.isTerms()).isTrue();
            assertThat(filter.terms().field()).isEqualTo("origin.bp_id");
        }

        @Test
        void bpSetEmpty_failsFast_throwsIllegalStateException() {
            // mockIndexTypeAuthorization.checkAccess() is a no-op (Mockito mock does not throw)
            SearchItemClient sut = newClientWithPermissiveIndexTypeAuth();
            // Auth with no userroles and no bproles — getAllBusinessPartnerIdsWithAnyOf returns empty
            Authorization emptyAuth = new Authorization(Set.of(), Map.of());

            assertThatThrownBy(() ->
                    sut.searchMultiVersion(
                            List.of(indexType, indexTypeV2),
                            Query.of(q -> q.matchAll(m -> m)),
                            emptyAuth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invariant violated");
        }
    }

}
