package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.IndexingException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemProvider;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemResult;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

@Component
@Slf4j
class HttpSearchItemProvider implements SearchItemProvider {

    private static final String PATH = "/index-api/searchitems";
    private static final String PARAM_INDEX_TYPE = "index_type";
    private static final String PARAM_ORIGIN_ID = "origin_id";
    private static final String PARAM_ORIGIN_VERSION = "origin_version";
    private static final String PARAM_INDEX_MAJOR_VERSION = "index_major_version";
    private static final String UNSUPPORTED_INDEX_MAJOR_VERSION_CODE = "UNSUPPORTED_INDEX_MAJOR_VERSION";
    private static final String HEADER_INDEX_TYPE = "index-type";
    private static final String HEADER_INDEX_MAJOR_VERSION = "index-major-version";
    private static final String HEADER_INDEX_MINOR_VERSION = "index-minor-version";

    private final JeapOAuth2RestClientBuilderFactory jeapOAuth2RestClientBuilderFactory;
    private final RestClient noAuthRestClient;
    private final Map<String, RestClient> oauthRestClientsByClientId = new ConcurrentHashMap<>();
    private final Duration timeout;

    public HttpSearchItemProvider(RemoteDataProperties properties,
                           JeapOAuth2RestClientBuilderFactory jeapOAuth2RestClientBuilderFactory,
                           RestClient.Builder noAuthRestClientBuilder) {
        this.timeout = properties.getTimeout();
        this.jeapOAuth2RestClientBuilderFactory = jeapOAuth2RestClientBuilderFactory;
        this.noAuthRestClient = noAuthRestClientBuilder.build();
    }

    @Override
    public Optional<SearchItemResult> findSearchItem(String baseUri, String indexType, OriginReference originReference, String oauthClientId) {
        log.debug("Fetching search item from {}{} for indexType={} originId={}", baseUri, PATH, indexType, originReference.id());
        try {
            ResponseEntity<SearchItem<JsonNode>> response = fetch(getRestClient(oauthClientId), baseUri, builder -> {
                builder.queryParam(PARAM_INDEX_TYPE, indexType)
                        .queryParam(PARAM_ORIGIN_ID, originReference.id());
                if (originReference.version() != null) {
                    builder.queryParam(PARAM_ORIGIN_VERSION, originReference.version());
                }
            }, UnaryOperator.identity());
            return response.hasBody() ? Optional.of(toResult(response)) : Optional.empty();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.debug("Search item not found at {}{} for indexType={} originId={}", baseUri, PATH, indexType, originReference.id());
                return Optional.empty();
            }
            throw e;
        } catch (HttpServerErrorException e) {
            throw IndexingException.searchItemProviderServerError(baseUri, indexType, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw IndexingException.searchItemProviderNotReachable(baseUri, indexType, e);
        }
    }

    @Override
    public Optional<SearchItemResult> findCurrentSearchItem(String baseUri, String indexType, String originId, int indexMajorVersion, String oauthClientId) {
        log.debug("Fetching current search item from {}{} for indexType={} indexMajorVersion={} originId={}",
                baseUri, PATH, indexType, indexMajorVersion, originId);
        RestClient restClient = getCurrentSearchItemRestClient(oauthClientId);
        try {
            ResponseEntity<SearchItem<JsonNode>> response = fetch(restClient, baseUri, builder -> builder
                    .queryParam(PARAM_INDEX_TYPE, indexType)
                    .queryParam(PARAM_ORIGIN_ID, originId)
                    .queryParam(PARAM_INDEX_MAJOR_VERSION, indexMajorVersion),
                    // Reject before the body is read: a redirect or other non-error status rarely carries a SearchItem
                    spec -> spec.onStatus(status -> !status.is2xxSuccessful() && !status.isError(), (request, nonSuccess) -> {
                        throw CurrentSearchItemException.requestRejected(indexType, indexMajorVersion, originId,
                                nonSuccess.getStatusCode().value(), null);
                    }));
            return Optional.of(toCurrentResult(response, indexType, indexMajorVersion, originId));
        } catch (HttpClientErrorException e) {
            return handleCurrentSearchItemClientError(e, baseUri, indexType, indexMajorVersion, originId);
        } catch (HttpServerErrorException e) {
            throw IndexingException.searchItemProviderServerError(baseUri, indexType, e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw IndexingException.searchItemProviderNotReachable(baseUri, indexType, e);
        } catch (OAuth2AuthorizationException e) {
            throw IndexingException.searchItemProviderAuthorizationFailed(baseUri, indexType, oauthClientId, e);
        } catch (RestClientException e) {
            if (hasIoCause(e)) {
                throw IndexingException.searchItemProviderNotReachable(baseUri, indexType, e);
            }
            throw CurrentSearchItemException.mismatch(indexType, indexMajorVersion, originId,
                    "unreadable response: %s".formatted(e.getMessage()), e);
        }
    }

    private SearchItemResult toCurrentResult(ResponseEntity<SearchItem<JsonNode>> response, String indexType, int indexMajorVersion, String originId) {
        if (!response.hasBody()) {
            throw CurrentSearchItemException.mismatch(indexType, indexMajorVersion, originId,
                    "the provider answered %d without a body".formatted(response.getStatusCode().value()));
        }
        try {
            return toResult(response);
        } catch (IndexingException | NumberFormatException e) {
            throw CurrentSearchItemException.mismatch(indexType, indexMajorVersion, originId,
                    "invalid version headers: %s".formatted(e.getMessage()), e);
        }
    }

    private static Optional<SearchItemResult> handleCurrentSearchItemClientError(HttpClientErrorException e, String baseUri,
                                                                                 String indexType, int indexMajorVersion, String originId) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            log.debug("Current search item not found at {}{} for indexType={} originId={}", baseUri, PATH, indexType, originId);
            return Optional.empty();
        }
        if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT && UNSUPPORTED_INDEX_MAJOR_VERSION_CODE.equals(problemCode(e))) {
            throw CurrentSearchItemException.unsupportedIndexMajorVersion(indexType, indexMajorVersion, originId);
        }
        if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS || e.getStatusCode() == HttpStatus.REQUEST_TIMEOUT) {
            throw IndexingException.searchItemProviderTemporarilyUnavailable(baseUri, indexType, e.getStatusCode().value());
        }
        throw CurrentSearchItemException.requestRejected(indexType, indexMajorVersion, originId, e.getStatusCode().value(), e);
    }

    private static String problemCode(HttpClientErrorException e) {
        try {
            JsonNode problem = e.getResponseBodyAs(JsonNode.class);
            return problem == null ? null : problem.path("code").asString(null);
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    private static boolean hasIoCause(Throwable e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<SearchItem<JsonNode>> fetch(RestClient restClient, String baseUri, Consumer<UriBuilder> queryParams,
                                                       UnaryOperator<RestClient.ResponseSpec> statusHandlers) {
        return statusHandlers.apply(restClient.get()
                        .uri(baseUri + PATH, builder -> {
                            queryParams.accept(builder);
                            return builder.build();
                        })
                        .retrieve())
                .toEntity(new ParameterizedTypeReference<>() {
                });
    }

    private SearchItemResult toResult(ResponseEntity<SearchItem<JsonNode>> response) {
        HttpHeaders headers = response.getHeaders();
        int majorVersion = parseIntHeader(headers, HEADER_INDEX_MAJOR_VERSION);
        int minorVersion = parseIntHeader(headers, HEADER_INDEX_MINOR_VERSION);
        return new SearchItemResult(majorVersion, minorVersion, response.getBody(), headers.getFirst(HEADER_INDEX_TYPE));
    }

    private int parseIntHeader(HttpHeaders headers, String headerName) {
        String value = headers.getFirst(headerName);
        if (value == null) {
            throw IndexingException.missingResponseHeader(headerName);
        }
        return Integer.parseInt(value);
    }

    // Live events keep the factory's IllegalArgumentException, which the Kafka listener treats as temporary
    private RestClient getCurrentSearchItemRestClient(String oauthClientId) {
        try {
            return getRestClient(oauthClientId);
        } catch (IllegalArgumentException e) {
            throw IndexingException.oauthClientNotRegistered(oauthClientId, e);
        }
    }

    private RestClient getRestClient(String oauthClientId) {
        if (oauthClientId == null) {
            return noAuthRestClient;
        }
        return oauthRestClientsByClientId.computeIfAbsent(oauthClientId, this::createRestClient);
    }

    private RestClient createRestClient(String oauthClientId) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                .withReadTimeout(timeout));
        RestClient.Builder oAuth2RestClientBuilder = jeapOAuth2RestClientBuilderFactory.createForClientRegistryId(oauthClientId);
        return oAuth2RestClientBuilder.requestFactory(requestFactory).build();
    }
}
