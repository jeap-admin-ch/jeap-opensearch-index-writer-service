package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.CurrentSearchItemException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.IndexingException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemResult;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import ch.admin.bit.jeap.security.restclient.JeapOAuth2RestClientBuilderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpSearchItemProviderTest {

    private static final String BASE_URI = "https://example.com/api";
    private static final String INDEX_TYPE = "PreziusRegistration";
    private static final String ORIGIN_ID = "reg-42";
    private static final String CURRENT_ITEM_BODY = """
            {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
            """;

    private MockRestServiceServer server;
    private HttpSearchItemProvider provider;
    private JeapOAuth2RestClientBuilderFactory oauthRestClientBuilderFactory;

    @BeforeEach
    void setUp() {
        RemoteDataProperties remoteDataProperties = new RemoteDataProperties();
        RestClient.Builder noAuthRestClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(noAuthRestClientBuilder).build();
        oauthRestClientBuilderFactory = mock(JeapOAuth2RestClientBuilderFactory.class);
        provider = new HttpSearchItemProvider(remoteDataProperties, oauthRestClientBuilderFactory, noAuthRestClientBuilder);
    }

    @Test
    void returnsSearchItemWithVersionHeadersWhenFound() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withVersionHeaders(withSuccess("""
                        {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON), 2, 3));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE,
                new OriginReference(INDEX_TYPE, ORIGIN_ID, null), null);

        assertThat(result).isPresent();
        assertThat(result.get().indexMajorVersion()).isEqualTo(2);
        assertThat(result.get().indexMinorVersion()).isEqualTo(3);
        assertThat(result.get().searchItem()).isNotNull();
    }

    @Test
    void includesOriginVersionWhenPresent() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&origin_version=v1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withVersionHeaders(withSuccess("""
                        {"origin":{"id":"reg-42","version":"v1","bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON), 1, 0));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE,
                new OriginReference(INDEX_TYPE, ORIGIN_ID, "v1"), null);

        assertThat(result).isPresent();
    }

    @Test
    void returnsEmptyWhenNotFound() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE,
                new OriginReference(INDEX_TYPE, ORIGIN_ID, null), null);

        assertThat(result).isEmpty();
    }

    private DefaultResponseCreator withVersionHeaders(DefaultResponseCreator creator, int major, int minor) {
        return creator.header("index-major-version", String.valueOf(major))
                      .header("index-minor-version", String.valueOf(minor));
    }

    @Test
    void throwsNonRetryableIndexingException_whenMajorVersionHeaderMissing() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withSuccess("""
                        {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON)
                        .header("index-minor-version", "3"));

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> !((IndexingException) e).isRetryable())
                .hasMessageContaining("index-major-version");
    }

    @Test
    void throwsNonRetryableIndexingException_whenMinorVersionHeaderMissing() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withSuccess("""
                        {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON)
                        .header("index-major-version", "2"));

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> !((IndexingException) e).isRetryable())
                .hasMessageContaining("index-minor-version");
    }

    @Test
    void throwsRetryableIndexingExceptionOnServerError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withServerError());

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void currentSearchItem_sendsSchemaMajorAndNeverOriginVersion() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("index_major_version", "1"))
                .andRespond(withVersionHeaders(withSuccess("""
                        {"origin":{"id":"reg-42","version":"rev-7","bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON), 1, 3).header("index-type", INDEX_TYPE));

        Optional<SearchItemResult> result = provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null);

        assertThat(result).isPresent();
        assertThat(result.get().indexType()).isEqualTo(INDEX_TYPE);
        assertThat(result.get().indexMajorVersion()).isEqualTo(1);
        assertThat(result.get().indexMinorVersion()).isEqualTo(3);
        server.verify();
    }

    @Test
    void currentSearchItem_returnsEmptyWhenNotFound() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null)).isEmpty();
    }

    @Test
    void currentSearchItem_throwsMismatchWhenSuccessHasNoBody() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withNoContent());

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("204 without a body")
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.RESPONSE_MISMATCH);
    }

    @Test
    void currentSearchItem_throwsRequestRejectedOnOtherClientError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("status 403")
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.REQUEST_REJECTED)
                .matches(e -> !((CurrentSearchItemException) e).isRetryable());
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"TOO_MANY_REQUESTS", "REQUEST_TIMEOUT"})
    void currentSearchItem_throwsRetryableIndexingExceptionForTemporaryClientErrors(HttpStatus status) {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withStatus(status));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(IndexingException.class)
                .hasMessageContaining(String.valueOf(status.value()))
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void currentSearchItem_throwsRequestRejectedForNonErrorStatusOutsideSuccessRange() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withVersionHeaders(withRawStatus(600).body(CURRENT_ITEM_BODY).contentType(MediaType.APPLICATION_JSON), 1, 0));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("600")
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.REQUEST_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 600})
    void currentSearchItem_throwsRequestRejectedForNonErrorStatusOutsideSuccessRangeWithUnreadableBody(int status) {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withRawStatus(status).body("<html>moved</html>").contentType(MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining("status " + status)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.REQUEST_REJECTED);
    }

    @Test
    void currentSearchItem_throwsMismatchWhenBodyIsUnreadable() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withVersionHeaders(withSuccess("not json", MediaType.APPLICATION_JSON), 1, 0));

        assertCurrentSearchItemMismatch("unreadable response");
    }

    @Test
    void currentSearchItem_throwsRetryableIndexingExceptionWhenBodyReadFails() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(request -> {
                    InputStream failingBody = new SequenceInputStream(
                            new ByteArrayInputStream("{\"origin\":{\"id\":\"reg-".getBytes(StandardCharsets.UTF_8)),
                            new InputStream() {
                                @Override
                                public int read() throws IOException {
                                    throw new IOException("Connection reset");
                                }
                            });
                    MockClientHttpResponse response = new MockClientHttpResponse(failingBody, HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    response.getHeaders().set("index-major-version", "1");
                    response.getHeaders().set("index-minor-version", "0");
                    return response;
                });

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void currentSearchItem_throwsMismatchWhenVersionHeaderMissing() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withSuccess(CURRENT_ITEM_BODY, MediaType.APPLICATION_JSON).header("index-major-version", "1"));

        assertCurrentSearchItemMismatch("index-minor-version");
    }

    @Test
    void currentSearchItem_throwsMismatchWhenVersionHeaderNotNumeric() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withSuccess(CURRENT_ITEM_BODY, MediaType.APPLICATION_JSON)
                        .header("index-major-version", "one")
                        .header("index-minor-version", "0"));

        assertCurrentSearchItemMismatch("invalid version headers");
    }

    private void assertCurrentSearchItemMismatch(String detail) {
        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .hasMessageContaining(detail)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.RESPONSE_MISMATCH);
    }

    @Test
    void currentSearchItem_throwsUnsupportedWhenProviderAnswers422() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=3"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"UNSUPPORTED_INDEX_MAJOR_VERSION\"}"));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 3, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.UNSUPPORTED_INDEX_MAJOR_VERSION)
                .matches(e -> !((CurrentSearchItemException) e).isRetryable());
    }

    @Test
    void currentSearchItem_throwsRequestRejectedWhen422HasOtherProblemCode() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=3"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"INVALID_ORIGIN_ID\"}"));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 3, null))
                .isInstanceOf(CurrentSearchItemException.class)
                .matches(e -> ((CurrentSearchItemException) e).getReason() == CurrentSearchItemException.Reason.REQUEST_REJECTED);
    }

    @Test
    void currentSearchItem_throwsRetryableIndexingExceptionOnServerError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42&index_major_version=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void liveLookupReportsNoIndexTypeWhenHeaderAbsent() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withVersionHeaders(withSuccess("""
                        {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON), 2, 3));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE, new OriginReference(INDEX_TYPE, ORIGIN_ID, null), null);

        assertThat(result).get().extracting(SearchItemResult::indexType).isNull();
    }

    @Test
    void throwsRetryableIndexingExceptionOnNetworkError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withException(new IOException("Connection refused")));

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref, null))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void currentSearchItem_throwsRetryableIndexingExceptionWhenOAuthTokenCannotBeObtained() {
        when(oauthRestClientBuilderFactory.createForClientRegistryId("my-client")).thenReturn(RestClient.builder()
                .requestInitializer(request -> {
                    throw new ClientAuthorizationException(new OAuth2Error("invalid_token_response"), "my-client");
                }));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, "my-client"))
                .isInstanceOf(IndexingException.class)
                .hasMessageContaining("OAuth token for client 'my-client'")
                .hasCauseInstanceOf(ClientAuthorizationException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void currentSearchItem_throwsNonRetryableIndexingExceptionWhenOAuthClientNotRegistered() {
        when(oauthRestClientBuilderFactory.createForClientRegistryId("unknown-client"))
                .thenThrow(new IllegalArgumentException("No client registration found"));

        assertThatThrownBy(() -> provider.findCurrentSearchItem(BASE_URI, INDEX_TYPE, ORIGIN_ID, 1, "unknown-client"))
                .isInstanceOf(IndexingException.class)
                .hasMessageContaining("No OAuth client registration found for client 'unknown-client'")
                .matches(e -> !((IndexingException) e).isRetryable());
    }

    @Test
    void liveLookupKeepsFactoryExceptionWhenOAuthClientNotRegistered() {
        when(oauthRestClientBuilderFactory.createForClientRegistryId("unknown-client"))
                .thenThrow(new IllegalArgumentException("No client registration found"));

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref, "unknown-client"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }
}
