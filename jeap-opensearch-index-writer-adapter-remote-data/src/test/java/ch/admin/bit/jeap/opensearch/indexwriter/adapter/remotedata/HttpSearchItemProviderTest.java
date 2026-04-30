package ch.admin.bit.jeap.opensearch.indexwriter.adapter.remotedata;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.IndexingException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.SearchItemResult;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpSearchItemProviderTest {

    private static final String BASE_URI = "https://example.com/api";
    private static final String INDEX_TYPE = "PreziusRegistration";
    private static final String ORIGIN_ID = "reg-42";

    private MockRestServiceServer server;
    private HttpSearchItemProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new HttpSearchItemProvider(builder.build());
    }

    @Test
    void returnsSearchItemWithVersionHeadersWhenFound() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withVersionHeaders(withSuccess("""
                        {"origin":{"id":"reg-42","version":null,"bp_id":null,"created":null,"modified":null,"reference":null},"data":{}}
                        """, MediaType.APPLICATION_JSON), 2, 3));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE,
                new OriginReference(INDEX_TYPE, ORIGIN_ID, null));

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
                new OriginReference(INDEX_TYPE, ORIGIN_ID, "v1"));

        assertThat(result).isPresent();
    }

    @Test
    void returnsEmptyWhenNotFound() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<SearchItemResult> result = provider.findSearchItem(BASE_URI, INDEX_TYPE,
                new OriginReference(INDEX_TYPE, ORIGIN_ID, null));

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
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref))
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
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref))
                .isInstanceOf(IndexingException.class)
                .matches(e -> !((IndexingException) e).isRetryable())
                .hasMessageContaining("index-minor-version");
    }

    @Test
    void throwsRetryableIndexingExceptionOnServerError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withServerError());

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }

    @Test
    void throwsRetryableIndexingExceptionOnNetworkError() {
        server.expect(requestTo(BASE_URI + "/index-api/searchitems?index_type=PreziusRegistration&origin_id=reg-42"))
                .andRespond(withException(new IOException("Connection refused")));

        OriginReference ref = new OriginReference(INDEX_TYPE, ORIGIN_ID, null);
        assertThatThrownBy(() -> provider.findSearchItem(BASE_URI, INDEX_TYPE, ref))
                .isInstanceOf(IndexingException.class)
                .matches(e -> ((IndexingException) e).isRetryable());
    }
}
