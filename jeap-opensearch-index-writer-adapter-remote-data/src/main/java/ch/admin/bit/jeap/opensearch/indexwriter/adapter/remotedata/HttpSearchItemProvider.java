package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.IndexingException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemProvider;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemResult;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
class HttpSearchItemProvider implements SearchItemProvider {

    private static final String PATH = "/index-api/searchitems";
    private static final String PARAM_INDEX_TYPE = "index_type";
    private static final String PARAM_ORIGIN_ID = "origin_id";
    private static final String PARAM_ORIGIN_VERSION = "origin_version";
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
            ResponseEntity<SearchItem<JsonNode>> response = getRestClient(oauthClientId).get()
                    .uri(baseUri + PATH,
                            builder -> {
                                builder.queryParam(PARAM_INDEX_TYPE, indexType)
                                        .queryParam(PARAM_ORIGIN_ID, originReference.id());
                                if (originReference.version() != null) {
                                    builder.queryParam(PARAM_ORIGIN_VERSION, originReference.version());
                                }
                                return builder.build();
                            })
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<>() {
                    });

            if (!response.hasBody()) {
                return Optional.empty();
            }

            int majorVersion = parseIntHeader(response.getHeaders(), HEADER_INDEX_MAJOR_VERSION);
            int minorVersion = parseIntHeader(response.getHeaders(), HEADER_INDEX_MINOR_VERSION);

            return Optional.of(new SearchItemResult(majorVersion, minorVersion, response.getBody()));

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

    private int parseIntHeader(HttpHeaders headers, String headerName) {
        String value = headers.getFirst(headerName);
        if (value == null) {
            throw IndexingException.missingResponseHeader(headerName);
        }
        return Integer.parseInt(value);
    }

    private RestClient getRestClient(String oauthClientId) {
        if (oauthClientId == null) {
            return noAuthRestClient;
        }
        return oauthRestClientsByClientId.computeIfAbsent(oauthClientId, this::createRestClient);
    }

    private RestClient createRestClient(String oauthClientId) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults().withConnectTimeout(timeout));
        RestClient.Builder oAuth2RestClientBuilder = jeapOAuth2RestClientBuilderFactory.createForClientRegistryId(oauthClientId);
        return oAuth2RestClientBuilder.requestFactory(requestFactory).build();
    }
}
