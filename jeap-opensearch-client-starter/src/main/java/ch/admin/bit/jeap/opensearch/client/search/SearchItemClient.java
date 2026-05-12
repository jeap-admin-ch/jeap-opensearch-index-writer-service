package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.Authorization;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAccessDeniedException;
import ch.admin.bit.jeap.opensearch.client.auth.IndexTypeAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.SearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.auth.UserSearchItemAuthorization;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.client.filter.OriginFilter;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Type-safe, authorization-aware read access to OpenSearch indices.
 *
 * <p>The {@code read}/{@code search} API is split into three stages so the
 * call-site is explicit about whether and how authorization is enforced:
 * <ul>
 *     <li>{@code …Unchecked(…)} — no auth checks. Building-block for the other stages
 *         but also usable for admin endpoints, reporting, batch jobs, unit tests.</li>
 *     <li>{@code …(…, auth)} — explicit {@link Authorization}. {@code auth == null}
 *         throws {@link IndexTypeAccessDeniedException#noAuthorization}; otherwise
 *         pre-filter (via {@link Authorization#getAllBusinessPartnerIdsWithAnyOf}) and
 *         post-filter (via {@link SearchItemAuthorization#filterByAuthorization}).</li>
 *     <li>{@code …WithUserAuth(…)} — pulls the {@link Authorization} from
 *         {@link UserSearchItemAuthorization#getUserAuthorization()} and delegates to
 *         the auth-stage; a {@code null} from the provider produces the same
 *         {@code noAuthorization} exception as an explicit {@code null}.</li>
 * </ul>
 *
 * <p>{@code IOException} and {@link OpenSearchException} from the underlying client are
 * wrapped in {@link SearchItemClientException} so the public API is checked-exception
 * free. The wrapping lives exclusively inside {@code searchUnchecked(...)} — all other
 * stages route through it.
 */
@RequiredArgsConstructor
public class SearchItemClient {

    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;
    private final IndexTypeAuthorization indexTypeAuthorization;
    private final SearchItemAuthorization searchItemAuthorization;
    private final UserSearchItemAuthorization userSearchItemAuthorization;

    // ============================================================================
    // read without any authorization checks
    // ============================================================================

    public <T> Optional<SearchItemTyped<T>> readUnchecked(
            IndexType<T> indexType, List<String> indices, String id) {
        List<SearchItemTyped<T>> hits =
                searchUnchecked(indexType, indices, idQuery(id), b -> b.size(2));
        return toReadResult(indexType, indices, id, hits);
    }

    public <T> Optional<SearchItemTyped<T>> readUnchecked(IndexType<T> indexType, String id) {
        return readUnchecked(indexType, List.of(indexType.indexReadAlias()), id);
    }

    // ============================================================================
    // read with check against explicit Authorization
    // ============================================================================

    public <T> Optional<SearchItemTyped<T>> read(
            IndexType<T> indexType, List<String> indices, String id, Authorization auth) {
        List<SearchItemTyped<T>> hits =
                search(indexType, indices, idQuery(id), b -> b.size(2), auth);
        return toReadResult(indexType, indices, id, hits);
    }

    public <T> Optional<SearchItemTyped<T>> read(
            IndexType<T> indexType, String id, Authorization auth) {
        return read(indexType, List.of(indexType.indexReadAlias()), id, auth);
    }

    // ============================================================================
    // read with check against the user's Authorization
    // ============================================================================

    public <T> Optional<SearchItemTyped<T>> readWithUserAuth(
            IndexType<T> indexType, List<String> indices, String id) {
        return read(indexType, indices, id, userSearchItemAuthorization.getUserAuthorization());
    }

    public <T> Optional<SearchItemTyped<T>> readWithUserAuth(IndexType<T> indexType, String id) {
        return readWithUserAuth(indexType, List.of(indexType.indexReadAlias()), id);
    }

    // ============================================================================
    // search without any authorization checks
    // ============================================================================

    public <T> List<SearchItemTyped<T>> searchUnchecked(
            IndexType<T> indexType, List<String> indices, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(indices)
                .query(query);
        if (searchRequestCustomizer != null) {
            searchRequestCustomizer.accept(builder);
        }
        SearchRequest request = builder.build();

        SearchResponse<JsonNode> response = executeSearch(request, indexType, indices);
        List<SearchItemTyped<T>> items = new ArrayList<>();
        for (Hit<JsonNode> hit : response.hits().hits()) {
            toSearchItem(hit, indexType).ifPresent(items::add);
        }
        return items;
    }

    public <T> List<SearchItemTyped<T>> searchUnchecked(
            IndexType<T> indexType, List<String> indices, Query query) {
        return searchUnchecked(indexType, indices, query, null);
    }

    public <T> List<SearchItemTyped<T>> searchUnchecked(
            IndexType<T> indexType, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        return searchUnchecked(indexType, List.of(indexType.indexReadAlias()), query,
                searchRequestCustomizer);
    }

    public <T> List<SearchItemTyped<T>> searchUnchecked(IndexType<T> indexType, Query query) {
        return searchUnchecked(indexType, List.of(indexType.indexReadAlias()), query, null);
    }

    // ============================================================================
    // search with check against explicit Authorization
    // ============================================================================

    public <T> List<SearchItemTyped<T>> search(
            IndexType<T> indexType, List<String> indices, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer, Authorization auth) {

        indexTypeAuthorization.checkAccess(indexType, auth);

        Query effectiveQuery = applyBpPreFilterIfNeeded(indexType, query, auth);

        List<SearchItemTyped<T>> items =
                searchUnchecked(indexType, indices, effectiveQuery, searchRequestCustomizer);

        return searchItemAuthorization.filterByAuthorization(items, auth);
    }

    public <T> List<SearchItemTyped<T>> search(
            IndexType<T> indexType, List<String> indices, Query query, Authorization auth) {
        return search(indexType, indices, query, null, auth);
    }

    public <T> List<SearchItemTyped<T>> search(
            IndexType<T> indexType, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer, Authorization auth) {
        return search(indexType, List.of(indexType.indexReadAlias()), query,
                searchRequestCustomizer, auth);
    }

    public <T> List<SearchItemTyped<T>> search(
            IndexType<T> indexType, Query query, Authorization auth) {
        return search(indexType, List.of(indexType.indexReadAlias()), query, null, auth);
    }

    // ============================================================================
    // search with check against the user's Authorization
    // ============================================================================

    public <T> List<SearchItemTyped<T>> searchWithUserAuth(
            IndexType<T> indexType, List<String> indices, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        return search(indexType, indices, query, searchRequestCustomizer,
                userSearchItemAuthorization.getUserAuthorization());
    }

    public <T> List<SearchItemTyped<T>> searchWithUserAuth(
            IndexType<T> indexType, List<String> indices, Query query) {
        return searchWithUserAuth(indexType, indices, query, null);
    }

    public <T> List<SearchItemTyped<T>> searchWithUserAuth(
            IndexType<T> indexType, Query query,
            Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        return searchWithUserAuth(indexType, List.of(indexType.indexReadAlias()), query,
                searchRequestCustomizer);
    }

    public <T> List<SearchItemTyped<T>> searchWithUserAuth(IndexType<T> indexType, Query query) {
        return searchWithUserAuth(indexType, List.of(indexType.indexReadAlias()), query, null);
    }

    // ============================================================================
    // private helpers
    // ============================================================================

    private static Query idQuery(String id) {
        return Query.of(q -> q.term(t -> t
                .field("origin.id")
                .value(FieldValue.of(id))));
    }

    /**
     * Maps a {@code read}-style result list to an {@link Optional}, enforcing uniqueness.
     *
     * <p>Note (V2): the uniqueness check is performed against the <em>caller-visible</em>
     * list — for {@code readUnchecked} that is the raw OpenSearch result (global
     * uniqueness), for {@code read(..., auth)} and {@code readWithUserAuth} that is the
     * post-filter result (authorization-scope uniqueness). The asymmetry is intentional:
     * it preserves information-disclosure boundaries while still allowing admin/reporting
     * paths to detect global data inconsistencies via {@code readUnchecked} or a
     * {@code read} call with a global-role authorization. See spec V2 section 7.1.
     */
    private <T> Optional<SearchItemTyped<T>> toReadResult(
            IndexType<T> indexType, List<String> indices, String id,
            List<SearchItemTyped<T>> hits) {
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        if (hits.size() == 1) {
            return Optional.of(hits.getFirst());
        }
        throw new MultipleSearchItemsFoundException(indexType, indices, id, hits.size());
    }

    /**
     * If the user has a global userrole on the index-type, the query is returned unchanged.
     * Otherwise a BP-pre-filter ({@link OriginFilter#forBusinessPartners}) is woven into a
     * {@code bool} wrapper so OpenSearch only returns items whose {@code origin.bp_id}
     * matches one of the user's authorised business partners.
     *
     * <p>Invariant: when the user has no global role, the BP set is guaranteed to be
     * non-empty because the preceding {@link IndexTypeAuthorization#checkAccess} already
     * proved that {@code auth.allRoles()} intersects {@code indexType.roles()} — and that
     * intersection must therefore come from {@code bproles}. The invariant is enforced
     * explicitly at runtime: if it is ever violated the method throws an
     * {@link IllegalStateException} instead of issuing an effectively unfiltered
     * OpenSearch query.
     */
    private Query applyBpPreFilterIfNeeded(IndexType<?> indexType, Query query, Authorization auth) {
        Set<String> requiredRoles = Set.copyOf(indexType.roles());
        if (auth.hasUserroleAnyOf(requiredRoles)) {
            return query;
        }
        Set<String> bps = auth.getAllBusinessPartnerIdsWithAnyOf(requiredRoles);
        if (bps.isEmpty()) {
            throw new IllegalStateException(
                    "Invariant violated: getAllBusinessPartnerIdsWithAnyOf(...) returned an"
                            + " empty set for index type '" + indexType.getClass().getSimpleName()
                            + "', but checkAccess(indexType, auth) succeeded without a global"
                            + " userrole — refusing to issue an unfiltered OpenSearch query.");
        }
        return Query.of(q -> q.bool(b -> b
                .must(query)
                .filter(OriginFilter.forBusinessPartners(bps))));
    }

    private <T> Optional<SearchItemTyped<T>> toSearchItem(Hit<JsonNode> hit, IndexType<T> indexType) {
        JsonNode src = hit.source();
        if (src == null) {
            return Optional.empty();
        }
        try {
            Origin origin = objectMapper.treeToValue(src.path("origin"), Origin.class);
            T data = objectMapper.treeToValue(src.path("data"), indexType.dataClass());
            return Optional.of(new SearchItemTyped<>(origin, data, indexType));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new SearchItemClientException(
                    "Failed to deserialize search item of index type '" + indexType.getClass().getSimpleName()
                            + "' (document id '" + hit.id() + "').", e);
        }
    }

    private SearchResponse<JsonNode> executeSearch(
            SearchRequest request, IndexType<?> indexType, List<String> indices) {
        try {
            return openSearchClient.search(request, JsonNode.class);
        } catch (IOException e) {
            throw new SearchItemClientException(
                    "OpenSearch IO error for index type '" + indexType.getClass().getSimpleName()
                            + "' on indices " + indices + ".", e);
        } catch (OpenSearchException e) {
            throw new SearchItemClientException(
                    "OpenSearch returned an error for index type '" + indexType.getClass().getSimpleName()
                            + "' on indices " + indices + ": HTTP " + e.status()
                            + ", type '" + e.response().error().type()
                            + "', reason '" + e.response().error().reason() + "'.", e);
        }
    }
}
