package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAccessDeniedException;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.SearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserSearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestData;
import ch.admin.bit.jeap.opensearch.client.search.SearchTestData.TestIndexType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchItemClientTest {

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private SearchItemAuthorization searchItemAuthorization;

    @Mock
    private UserSearchItemAuthorization userSearchItemAuthorization;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexTypeAuthorization indexTypeAuthorization = new IndexTypeAuthorization();

    private TestIndexType indexType;
    private Authorization globalAuth;

    @BeforeEach
    void setUp() {
        indexType = new TestIndexType(List.of("inspection_read"));
        globalAuth = new Authorization(Set.of("inspection_read"), Map.of());
    }

    private SearchItemClient newClient() {
        return new SearchItemClient(
                openSearchClient,
                objectMapper,
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
    class SearchUnchecked {

        @Test
        void noAuthChecks_passThroughEmptyAuth() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            List<SearchItemTyped<TestData>> result = sut.searchUnchecked(
                    indexType, List.of("idx_v1"),
                    Query.of(q -> q.matchAll(m -> m)), null);

            assertThat(result).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(userSearchItemAuthorization);
            org.mockito.Mockito.verifyNoInteractions(searchItemAuthorization);
        }

        @Test
        void deserialisesHits_andDoesNotFilterByAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            JsonNode s2 = SearchTestData.sourceJson(objectMapper, "id-2", "BP2", "beta");
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));

            List<SearchItemTyped<TestData>> result = sut.searchUnchecked(
                    indexType, List.of("idx_v1"), Query.of(q -> q.matchAll(m -> m)));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(it -> it.origin().id()).containsExactly("id-1", "id-2");
            org.mockito.Mockito.verifyNoInteractions(searchItemAuthorization);
        }

        @Test
        void customizer_appliedAfterIndexAndQuery() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.searchUnchecked(indexType, List.of("idx_v1"),
                    Query.of(q -> q.matchAll(m -> m)), b -> b.size(20));

            SearchRequest req = captureSearchRequest();
            assertThat(req.size()).isEqualTo(20);
            assertThat(req.index()).containsExactly("idx_v1");
        }

        @Test
        void nullCustomizer_isAccepted() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.searchUnchecked(indexType, List.of("idx_v1"), Query.of(q -> q.matchAll(m -> m)), null);

            captureSearchRequest();
        }

        @Test
        void convenienceWithoutIndices_usesIndexReadAlias() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.searchUnchecked(indexType, Query.of(q -> q.matchAll(m -> m)));

            assertThat(captureSearchRequest().index()).containsExactly(indexType.indexReadAlias());
        }

        @Test
        void allFourOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.searchUnchecked(indexType, List.of("idx_v1"), q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchUnchecked(indexType, List.of("idx_v1"), q)).isEmpty();
            assertThat(sut.searchUnchecked(indexType, q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchUnchecked(indexType, q)).isEmpty();
        }

        @Test
        void hitWithNullSource_isSilentlyDropped() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode source = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            whenSearchReturnsHits(List.of(mockHit("d1", source), mockHit("d2", null)));

            List<SearchItemTyped<TestData>> result = sut.searchUnchecked(
                    indexType, List.of("idx_v1"), Query.of(q -> q.matchAll(m -> m)));

            assertThat(result).hasSize(1);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    @Nested
    class SearchWithAuth {

        @Test
        void nullAuth_throwsNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            Authorization nullAuth = null;
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.search(indexType, indices, query, nullAuth))
                    .isInstanceOfSatisfying(IndexTypeAccessDeniedException.class, ex -> {
                        assertThat(ex.getIndexType()).isSameAs(indexType);
                        assertThat(ex.getMessage()).contains("no authorization");
                    });

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void notAuthorized_throwsIndexTypeAccessDenied() throws IOException {
            SearchItemClient sut = newClient();
            Authorization wrongAuth = new Authorization(Set.of("other_role"), Map.of());
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.search(indexType, indices, query, wrongAuth))
                    .isInstanceOfSatisfying(IndexTypeAccessDeniedException.class,
                            ex -> assertThat(ex.getMessage())
                                    .contains(indexType.getClass().getSimpleName()));

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void globalUserrole_noBpPreFilterApplied_queryPassedThroughUnchanged() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth)))
                    .thenAnswer(inv -> inv.getArgument(0));
            Query original = Query.of(q -> q.matchAll(m -> m));

            sut.search(indexType, List.of("idx_v1"), original, globalAuth);

            SearchRequest req = captureSearchRequest();
            assertThat(req.query()).isSameAs(original);
        }

        @Test
        void bpOnlyAuth_appliesBpPreFilter_wrappingQueryInBool() throws IOException {
            // Expects bool { must: query, filter: BP-filter } when no global userrole grants access.
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            Authorization bpAuth = bpOnlyAuth("BP1");
            when(searchItemAuthorization.filterByAuthorization(any(), eq(bpAuth)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.search(indexType, List.of("idx_v1"),
                    Query.of(q -> q.matchAll(m -> m)), bpAuth);

            Query effective = captureSearchRequest().query();
            assertThat(effective.isBool()).isTrue();
            BoolQuery bool = effective.bool();
            assertThat(bool.must()).hasSize(1);
            assertThat(bool.must().getFirst().isMatchAll()).isTrue();
            assertThat(bool.filter()).hasSize(1);
            Query filter = bool.filter().getFirst();
            assertThat(filter.isTerms()).isTrue();
            TermsQuery terms = filter.terms();
            assertThat(terms.field()).isEqualTo("origin.bp_id");
            List<String> values = terms.terms().value().stream()
                    .map(fv -> fv.isString() ? fv.stringValue() : fv.toString())
                    .toList();
            assertThat(values).containsExactly("BP1");
        }

        @Test
        void multipleBps_areAllIncludedInPreFilter() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            Authorization multiBp = new Authorization(
                    Set.of(),
                    Map.of(
                            "BP1", Set.of("inspection_read"),
                            "BP2", Set.of("inspection_read"),
                            "BP3", Set.of("other_role")));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(multiBp)))
                    .thenAnswer(inv -> inv.getArgument(0));

            sut.search(indexType, List.of("idx_v1"),
                    Query.of(q -> q.matchAll(m -> m)), multiBp);

            BoolQuery bool = captureSearchRequest().query().bool();
            List<String> values = bool.filter().getFirst().terms().terms().value().stream()
                    .map(fv -> fv.isString() ? fv.stringValue() : fv.toString())
                    .toList();
            assertThat(values).containsExactlyInAnyOrder("BP1", "BP2");
        }

        @Test
        void bpSetEmpty_failsFast_throwsIllegalStateException() throws IOException {
            // If a mocked checkAccess lets a no-userrole/empty-BP auth through, the pre-filter
            // stage must refuse rather than issue an effectively unfiltered OpenSearch query.
            IndexTypeAuthorization mockedIndexTypeAuth = org.mockito.Mockito.mock(IndexTypeAuthorization.class);
            SearchItemClient sut = new SearchItemClient(
                    openSearchClient, objectMapper, mockedIndexTypeAuth,
                    searchItemAuthorization, userSearchItemAuthorization);
            Authorization emptyAuth = new Authorization(Set.of(), Map.of());
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.search(indexType, indices, query, emptyAuth))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invariant violated")
                    .hasMessageContaining(indexType.getClass().getSimpleName());

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void postFilter_dropsUnauthorisedItems() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            JsonNode s2 = SearchTestData.sourceJson(objectMapper, "id-2", "BP2", "beta");
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth)))
                    .thenAnswer(inv -> {
                        List<SearchItemTyped<TestData>> input = inv.getArgument(0);
                        return input.stream().filter(it -> !"id-2".equals(it.origin().id())).toList();
                    });

            List<SearchItemTyped<TestData>> result = sut.search(
                    indexType, List.of("idx_v1"),
                    Query.of(q -> q.matchAll(m -> m)), globalAuth);

            assertThat(result).extracting(it -> it.origin().id()).containsExactly("id-1");
        }

        @Test
        void allFourOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), any())).thenReturn(List.of());
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.search(indexType, List.of("idx_v1"), q, b -> b.size(5), globalAuth)).isEmpty();
            assertThat(sut.search(indexType, List.of("idx_v1"), q, globalAuth)).isEmpty();
            assertThat(sut.search(indexType, q, b -> b.size(5), globalAuth)).isEmpty();
            assertThat(sut.search(indexType, q, globalAuth)).isEmpty();
        }
    }

    @Nested
    class SearchWithUserAuth {

        @Test
        void providerNull_routesIntoNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(null);
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchWithUserAuth(indexType, indices, query))
                    .isInstanceOf(IndexTypeAccessDeniedException.class);

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void providerReturnsAuth_isThreadedThroughToPostFilter() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth))).thenReturn(List.of());

            sut.searchWithUserAuth(indexType, List.of("idx_v1"), Query.of(q -> q.matchAll(m -> m)));

            verify(searchItemAuthorization).filterByAuthorization(any(), eq(globalAuth));
        }

        @Test
        void allFourOverloadsCompile_andRun() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), any())).thenReturn(List.of());
            Query q = Query.of(qb -> qb.matchAll(m -> m));

            assertThat(sut.searchWithUserAuth(indexType, List.of("idx_v1"), q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchWithUserAuth(indexType, List.of("idx_v1"), q)).isEmpty();
            assertThat(sut.searchWithUserAuth(indexType, q, b -> b.size(5))).isEmpty();
            assertThat(sut.searchWithUserAuth(indexType, q)).isEmpty();
        }

        @Test
        void convenienceWithoutIndices_usesIndexReadAlias() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), any())).thenReturn(List.of());

            sut.searchWithUserAuth(indexType, Query.of(q -> q.matchAll(m -> m)));

            assertThat(captureSearchRequest().index()).containsExactly(indexType.indexReadAlias());
        }
    }

    @Nested
    class ReadUnchecked {

        @Test
        void zeroHits_returnsEmpty() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            Optional<SearchItemTyped<TestData>> result =
                    sut.readUnchecked(indexType, List.of("idx_v1"), "id-1");

            assertThat(result).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(searchItemAuthorization, userSearchItemAuthorization);
        }

        @Test
        void oneHit_returnsItem_andDoesNotCheckAuth() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode src = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            whenSearchReturnsHits(List.of(mockHit("d1", src)));

            Optional<SearchItemTyped<TestData>> result =
                    sut.readUnchecked(indexType, List.of("idx_v1"), "id-1");

            assertThat(result).isPresent();
            assertThat(result.orElseThrow().origin().id()).isEqualTo("id-1");
            org.mockito.Mockito.verifyNoInteractions(searchItemAuthorization, userSearchItemAuthorization);
        }

        @Test
        void twoHits_throwsMultipleSearchItemsFoundException() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            JsonNode s2 = SearchTestData.sourceJson(objectMapper, "id-1", "BP2", "beta");
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));
            List<String> indices = List.of("idx_v1");

            assertThatThrownBy(() ->
                    sut.readUnchecked(indexType, indices, "id-1"))
                    .isInstanceOfSatisfying(MultipleSearchItemsFoundException.class,
                            ex -> assertThat(ex.getMessage())
                                    .contains("id-1")
                                    .contains(indexType.getClass().getSimpleName())
                                    .contains("2"));
        }

        @Test
        void convenienceWithoutIndices_usesIndexReadAlias() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.readUnchecked(indexType, "id-1");

            assertThat(captureSearchRequest().index()).containsExactly(indexType.indexReadAlias());
        }

        @Test
        void buildsSearchRequest_withSize2() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());

            sut.readUnchecked(indexType, List.of("idx_v1"), "id-1");

            assertThat(captureSearchRequest().size()).isEqualTo(2);
        }
    }

    @Nested
    class ReadWithAuth {

        @Test
        void nullAuth_throwsNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            List<String> indices = List.of("idx_v1");

            assertThatThrownBy(() ->
                    sut.read(indexType, indices, "id-1", null))
                    .isInstanceOfSatisfying(IndexTypeAccessDeniedException.class,
                            ex -> assertThat(ex.getMessage()).contains("no authorization"));

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void notAuthorized_throwsIndexTypeAccessDenied() {
            SearchItemClient sut = newClient();
            Authorization wrongAuth = new Authorization(Set.of("other_role"), Map.of());
            List<String> indices = List.of("idx_v1");

            assertThatThrownBy(() ->
                    sut.read(indexType, indices, "id-1", wrongAuth))
                    .isInstanceOf(IndexTypeAccessDeniedException.class);
        }

        @Test
        void unauthorisedItem_postFilteredOut_returnsEmpty() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode src = SearchTestData.sourceJson(objectMapper, "id-1", "BP_OTHER", "alpha");
            whenSearchReturnsHits(List.of(mockHit("d1", src)));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth)))
                    .thenReturn(List.of());

            Optional<SearchItemTyped<TestData>> result =
                    sut.read(indexType, List.of("idx_v1"), "id-1", globalAuth);

            assertThat(result).isEmpty();
        }

        @Test
        void authorizedItem_returnsItem() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode src = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            whenSearchReturnsHits(List.of(mockHit("d1", src)));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Optional<SearchItemTyped<TestData>> result =
                    sut.read(indexType, List.of("idx_v1"), "id-1", globalAuth);

            assertThat(result).isPresent();
            assertThat(result.orElseThrow().origin().id()).isEqualTo("id-1");
        }

        @Test
        void twoHits_throwsMultipleSearchItemsFoundException() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode s1 = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            JsonNode s2 = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "beta");
            whenSearchReturnsHits(List.of(mockHit("d1", s1), mockHit("d2", s2)));
            when(searchItemAuthorization.filterByAuthorization(any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            List<String> indices = List.of("idx_v1");

            assertThatThrownBy(() ->
                    sut.read(indexType, indices, "id-1", globalAuth))
                    .isInstanceOf(MultipleSearchItemsFoundException.class);
        }

        @Test
        void authScopeUniqueness_idAlsoInForeignBp_postFilterStripsForeignHit_returnsOnlyOwn() throws IOException {
            // The uniqueness check runs against the post-filtered list, not the raw hits.
            // When the same origin.id exists in an accessible and a foreign BP and both
            // surface from OpenSearch (e.g. customizer overrode the pre-filter), the
            // post-filter resolves it without MultipleSearchItemsFoundException.
            SearchItemClient sut = newClient();
            JsonNode ownHit = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "own");
            JsonNode foreignHit = SearchTestData.sourceJson(objectMapper, "id-1", "BP2", "foreign");
            whenSearchReturnsHits(List.of(mockHit("d1", ownHit), mockHit("d2", foreignHit)));
            Authorization bp1Auth = new Authorization(
                    Set.of(), Map.of("BP1", Set.of("inspection_read")));
            when(searchItemAuthorization.filterByAuthorization(any(), eq(bp1Auth)))
                    .thenAnswer(inv -> {
                        List<SearchItemTyped<TestData>> input = inv.getArgument(0);
                        return input.stream()
                                .filter(it -> "BP1".equals(it.origin().bpId()))
                                .toList();
                    });

            Optional<SearchItemTyped<TestData>> result =
                    sut.read(indexType, List.of("idx_v1"), "id-1", bp1Auth);

            assertThat(result).isPresent();
            assertThat(result.orElseThrow().origin().bpId()).isEqualTo("BP1");
            assertThat(result.orElseThrow().data().label()).isEqualTo("own");
        }

        @Test
        void convenienceWithoutIndices_usesIndexReadAlias() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(searchItemAuthorization.filterByAuthorization(any(), any())).thenReturn(List.of());

            sut.read(indexType, "id-1", globalAuth);

            assertThat(captureSearchRequest().index()).containsExactly(indexType.indexReadAlias());
        }
    }

    @Nested
    class ReadWithUserAuth {

        @Test
        void providerNull_throwsNoAuthorization() throws IOException {
            SearchItemClient sut = newClient();
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(null);
            List<String> indices = List.of("idx_v1");

            assertThatThrownBy(() ->
                    sut.readWithUserAuth(indexType, indices, "id-1"))
                    .isInstanceOf(IndexTypeAccessDeniedException.class);

            verify(openSearchClient, never()).search(any(SearchRequest.class), any());
        }

        @Test
        void providerReturnsAuth_resolvesItemSuccessfully() throws IOException {
            SearchItemClient sut = newClient();
            JsonNode src = SearchTestData.sourceJson(objectMapper, "id-1", "BP1", "alpha");
            whenSearchReturnsHits(List.of(mockHit("d1", src)));
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), eq(globalAuth)))
                    .thenAnswer(inv -> inv.getArgument(0));

            Optional<SearchItemTyped<TestData>> result =
                    sut.readWithUserAuth(indexType, List.of("idx_v1"), "id-1");

            assertThat(result).isPresent();
        }

        @Test
        void allTwoOverloadsCompile() throws IOException {
            SearchItemClient sut = newClient();
            whenSearchReturnsHits(List.of());
            when(userSearchItemAuthorization.getUserAuthorization()).thenReturn(globalAuth);
            when(searchItemAuthorization.filterByAuthorization(any(), any())).thenReturn(List.of());

            assertThat(sut.readWithUserAuth(indexType, List.of("idx_v1"), "id-1")).isEmpty();
            assertThat(sut.readWithUserAuth(indexType, "id-1")).isEmpty();
        }
    }

    @Nested
    class ExceptionWrapping {

        @Test
        void ioException_wrappedInSearchItemClientException_withCause() throws IOException {
            SearchItemClient sut = newClient();
            IOException ioe = new IOException("transport failed");
            when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                    .thenThrow(ioe);
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchUnchecked(indexType, indices, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex -> {
                        assertThat(ex.getCause()).isSameAs(ioe);
                        assertThat(ex.getMessage())
                                .contains(indexType.getClass().getSimpleName())
                                .contains("idx_v1");
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
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchUnchecked(indexType, indices, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex -> {
                        assertThat(ex.getCause()).isSameAs(ose);
                        assertThat(ex.getMessage()).contains("404").contains("index_not_found_exception");
                    });
        }

        @Test
        void deserializationError_wrappedInSearchItemClientException() throws IOException {
            SearchItemClient sut = newClient();
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode originNode = root.putObject("origin");
            originNode.put("id", "id-1");
            originNode.put("version", "1");
            originNode.putNull("bp_id");
            originNode.putNull("tenant");
            root.put("data", "not-an-object");
            whenSearchReturnsHits(List.of(mockHit("d1", root)));
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.searchUnchecked(indexType, indices, query))
                    .isInstanceOfSatisfying(SearchItemClientException.class, ex ->
                            assertThat(ex.getCause())
                                    .isInstanceOfAny(JsonProcessingException.class, IllegalArgumentException.class));
        }

        @Test
        void exceptionWrappingAlsoAppliesToAuthorizedSearches() throws IOException {
            // search(..., auth) routes through searchUnchecked — wrapping is shared.
            SearchItemClient sut = newClient();
            when(openSearchClient.search(any(SearchRequest.class), eq(JsonNode.class)))
                    .thenThrow(new IOException("boom"));
            List<String> indices = List.of("idx_v1");
            Query query = Query.of(q -> q.matchAll(m -> m));

            assertThatThrownBy(() ->
                    sut.search(indexType, indices, query, globalAuth))
                    .isInstanceOf(SearchItemClientException.class);
        }
    }
}
