package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.client.auth.*;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.client.domain.SearchItemView;
import ch.admin.bit.jeap.opensearch.client.filter.OriginFilter;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import tools.jackson.core.JacksonException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Authorization-aware multi-version search access to OpenSearch indices.
 *
 * <p>Each search method family comes in three auth stages:
 * <ul>
 *     <li>{@code …Unchecked(…)} — no auth checks.</li>
 *     <li>{@code …(…, auth)} — explicit {@link Authorization}. {@code auth == null}
 *         throws {@link IndexTypeAccessDeniedException}; otherwise applies a BP
 *         pre-filter and post-filter via {@link SearchItemAuthorization}.</li>
 *     <li>{@code …WithUserAuth(…)} — pulls {@link Authorization} from
 *         {@link UserSearchItemAuthorization#getUserAuthorization()} and delegates
 *         to the auth stage.</li>
 * </ul>
 *
 * <p>All provided {@link IndexType} instances must share the same system and origin type.
 * Authorization checks (pre-check, BP pre-filter, post-filter) all use the roles of the
 * <em>latest</em> version (highest majorVersion/minorVersion). Deserialization is dispatched
 * per document by {@code search_item.major_version}.
 * Documents missing that field or referencing an unknown version throw a
 * {@link SearchItemClientException}.
 *
 * <p>{@code IOException} and {@link OpenSearchException} are wrapped in
 * {@link SearchItemClientException}.
 */
@Slf4j
@RequiredArgsConstructor
public class SearchItemClient {

    private final OpenSearchClient openSearchClient;
    private final JsonMapper jsonMapper;
    private final IndexTypeAuthorization indexTypeAuthorization;
    private final SearchItemAuthorization searchItemAuthorization;
    private final UserSearchItemAuthorization userSearchItemAuthorization;

    // ============================================================================
    // multi-version search without any authorization checks (typed)
    // ============================================================================

    /**
     * Searches across multiple versions of the same index type, dispatching deserialization
     * to the matching {@link IndexType} based on {@code search_item.major_version} in each
     * document. All provided {@link IndexType} instances must share the same system and origin
     * type. The index is derived from the shared {@link IndexType#indexReadAlias()}.
     * Documents without {@code search_item.major_version} or with an unknown major version
     * throw a {@link SearchItemClientException}.
     */
    public List<SearchItemView> searchMultiVersionUnchecked(List<IndexType<?>> indexTypes, Query query,
                                                                Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        IndexType<?> latest = validateAndGetLatest(indexTypes);
        Map<Integer, IndexType<?>> byMajor = indexTypesByMajor(indexTypes);
        return doSearchMultiVersionTyped(byMajor, latest, allReadAliases(indexTypes), query,
                searchRequestCustomizer);
    }

    /**
     * Convenience overload of {@link #searchMultiVersionUnchecked(List, Query, Consumer)}
     * without a request customizer.
     */
    public List<SearchItemView> searchMultiVersionUnchecked(
            List<IndexType<?>> indexTypes, Query query) {
        return searchMultiVersionUnchecked(indexTypes, query, null);
    }

    // ============================================================================
    // multi-version search with check against explicit Authorization (typed)
    // ============================================================================

    /**
     * Authorization-checked multi-version search against an explicit {@link Authorization}.
     *
     * <p>Steps performed:
     * <ol>
     *   <li>Validates {@code indexTypes} and resolves the latest version (see class Javadoc).</li>
     *   <li>Calls {@link IndexTypeAuthorization#checkAccess} — throws
     *       {@link IndexTypeAccessDeniedException} if {@code auth} is {@code null} or lacks
     *       the required roles.</li>
     *   <li>Wraps {@code query} in a BP pre-filter when the caller has only BP-scoped roles
     *       (no global userrole), so OpenSearch only returns items for the caller's authorised
     *       business partners.</li>
     *   <li>Executes the search and post-filters results via
     *       {@link SearchItemAuthorization#filterByAuthorization}, using the latest version's
     *       roles for all items.</li>
     * </ol>
     *
     * @param indexTypes              the index-type versions to search across; must be non-empty
     *                                and share the same system and origin type
     * @param query                   the OpenSearch query to execute
     * @param searchRequestCustomizer optional callback to further configure the
     *                                {@link SearchRequest.Builder} (may be {@code null})
     * @param auth                    the caller's authorization; {@code null} causes an
     *                                {@link IndexTypeAccessDeniedException}
     * @return authorized search results, deserialized and dispatched by major version
     * @throws IndexTypeAccessDeniedException if the caller is not authorized for the index type
     * @throws SearchItemClientException      if {@code indexTypes} is invalid, a document is
     *                                        missing {@code search_item.major_version}, or an
     *                                        OpenSearch I/O or API error occurs
     */
    public List<SearchItemView> searchMultiVersion(List<IndexType<?>> indexTypes, Query query,
                                                       Consumer<SearchRequest.Builder> searchRequestCustomizer, Authorization auth) {
        IndexType<?> latest = validateAndGetLatest(indexTypes);
        Map<Integer, IndexType<?>> byMajor = indexTypesByMajor(indexTypes);
        indexTypeAuthorization.checkAccess(latest, auth);
        Query effectiveQuery = applyBpPreFilterIfNeeded(latest, query, auth);
        List<SearchItemView> items = doSearchMultiVersionTyped(byMajor, latest,
                allReadAliases(indexTypes), effectiveQuery, searchRequestCustomizer);
        return searchItemAuthorization.filterByAuthorization(items, auth, latest.roles());
    }

    /**
     * Convenience overload of {@link #searchMultiVersion(List, Query, Consumer, Authorization)}
     * without a request customizer.
     */
    public List<SearchItemView> searchMultiVersion(List<IndexType<?>> indexTypes, Query query, Authorization auth) {
        return searchMultiVersion(indexTypes, query, null, auth);
    }

    // ============================================================================
    // multi-version search with check against the user's Authorization (typed)
    // ============================================================================

    /**
     * Retrieves the current user's {@link Authorization} from the configured
     * {@link ch.admin.bit.jeap.opensearch.client.auth.UserAuthorizationProvider} and
     * delegates to {@link #searchMultiVersion(List, Query, Consumer, Authorization)}.
     *
     * @param indexTypes              the index-type versions to search across; must be non-empty
     *                                and share the same system and origin type
     * @param query                   the OpenSearch query to execute
     * @param searchRequestCustomizer optional callback to further configure the
     *                                {@link SearchRequest.Builder} (may be {@code null})
     * @return authorized search results for the current user
     * @throws IndexTypeAccessDeniedException if the current user is not authorized for the index type
     * @throws SearchItemClientException      if {@code indexTypes} is invalid, a document is
     *                                        missing {@code search_item.major_version}, or an
     *                                        OpenSearch I/O or API error occurs
     */
    public List<SearchItemView> searchMultiVersionWithUserAuth(List<IndexType<?>> indexTypes, Query query,
                                                                   Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        return searchMultiVersion(indexTypes, query, searchRequestCustomizer, userSearchItemAuthorization.getUserAuthorization());
    }

    /**
     * Convenience overload of {@link #searchMultiVersionWithUserAuth(List, Query, Consumer)}
     * without a request customizer.
     */
    public List<SearchItemView> searchMultiVersionWithUserAuth(List<IndexType<?>> indexTypes, Query query) {
        return searchMultiVersionWithUserAuth(indexTypes, query, null);
    }

    // ============================================================================
    // private helpers
    // ============================================================================

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
            Origin origin = jsonMapper.treeToValue(src.path("origin"), Origin.class);
            T data = jsonMapper.treeToValue(src.path("data"), indexType.dataClass());
            return Optional.of(new SearchItemTyped<>(origin, data, indexType));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new SearchItemClientException(
                    "Failed to deserialize search item of index type '" + indexType.getClass().getSimpleName()
                            + "' (document id '" + hit.id() + "').", e);
        }
    }

    private SearchResponse<JsonNode> executeSearch(SearchRequest request, IndexType<?> indexType, List<String> indices) {
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

    /**
     * Validates that all provided {@link IndexType} instances share the same system and origin
     * type, then returns the instance with the highest {@code (majorVersion, minorVersion)}.
     * Roles are taken from the returned latest version for all authorization decisions
     * (pre-check, BP pre-filter, post-filter) and are not required to be identical across versions.
     *
     * @throws SearchItemClientException if {@code indexTypes} is null/empty, or if any instance
     *                                   differs in system/originType
     */
    private static IndexType<?> validateAndGetLatest(List<IndexType<?>> indexTypes) {
        if (indexTypes == null || indexTypes.isEmpty()) {
            throw new SearchItemClientException("indexTypes must not be null or empty");
        }
        IndexType<?> first = indexTypes.getFirst();
        String system = first.system();
        String originType = first.originType();
        IndexType<?> latest = first;
        for (IndexType<?> indexType : indexTypes) {
            if (!indexType.system().equals(system) || !indexType.originType().equals(originType)) {
                throw new SearchItemClientException(
                        "All index types must share the same system and origin type, but '"
                                + indexType.getClass().getSimpleName() + "' has '"
                                + indexType.system() + "/" + indexType.originType()
                                + "' while the first has '" + system + "/" + originType + "'.");
            }
            if (indexType.majorVersion() > latest.majorVersion()
                    || (indexType.majorVersion() == latest.majorVersion()
                    && indexType.minorVersion() > latest.minorVersion())) {
                latest = indexType;
            }
        }
        return latest;
    }

    private static List<String> allReadAliases(List<IndexType<?>> indexTypes) {
        return indexTypes.stream().map(IndexType::indexReadAlias).distinct().toList();
    }

    /**
     * Builds a map from {@code majorVersion} to {@link IndexType}, validating that no two
     * entries share the same major version.
     *
     * @throws SearchItemClientException if two index types have the same major version
     */
    private static Map<Integer, IndexType<?>> indexTypesByMajor(List<IndexType<?>> indexTypes) {
        Map<Integer, IndexType<?>> map = new HashMap<>();
        for (IndexType<?> indexType : indexTypes) {
            IndexType<?> existing = map.put(indexType.majorVersion(), indexType);
            if (existing != null) {
                throw new SearchItemClientException(
                        "Duplicate major version " + indexType.majorVersion()
                                + " in provided index types ('"
                                + existing.getClass().getSimpleName() + "' and '"
                                + indexType.getClass().getSimpleName() + "').");
            }
        }
        return map;
    }

    private List<SearchItemView> doSearchMultiVersionTyped(Map<Integer, IndexType<?>> byMajor, IndexType<?> latest,
                                                           List<String> indices, Query query, Consumer<SearchRequest.Builder> searchRequestCustomizer) {
        SearchRequest.Builder builder = new SearchRequest.Builder().index(indices).query(query);
        if (searchRequestCustomizer != null) {
            searchRequestCustomizer.accept(builder);
        }
        SearchResponse<JsonNode> response = executeSearch(builder.build(), latest, indices);
        List<SearchItemView> items = new ArrayList<>();
        for (Hit<JsonNode> hit : response.hits().hits()) {
            toMultiVersionTypedItem(hit, byMajor).ifPresent(items::add);
        }
        return items;
    }

    private Optional<SearchItemView> toMultiVersionTypedItem(Hit<JsonNode> hit, Map<Integer, IndexType<?>> indexTypesByMajor) {
        JsonNode src = hit.source();
        if (src == null) {
            return Optional.empty();
        }
        JsonNode searchItemNode = src.path("search_item");
        if (searchItemNode.isMissingNode() || searchItemNode.path("major_version").isMissingNode()) {
            throw new SearchItemClientException(
                    "Document '" + hit.id() + "' is missing the required 'search_item.major_version' field.");
        }
        int majorVersion = searchItemNode.path("major_version").asInt();
        IndexType<?> indexType = indexTypesByMajor.get(majorVersion);
        if (indexType == null) {
            throw new SearchItemClientException(
                    "Document '" + hit.id() + "' has major_version=" + majorVersion
                            + " but no matching IndexType was provided in the search request.");
        }
        return captureAndDeserialize(hit, indexType);
    }

    private <T> Optional<SearchItemView> captureAndDeserialize(Hit<JsonNode> hit, IndexType<T> indexType) {
        return toSearchItem(hit, indexType).map(item -> item);
    }
}
