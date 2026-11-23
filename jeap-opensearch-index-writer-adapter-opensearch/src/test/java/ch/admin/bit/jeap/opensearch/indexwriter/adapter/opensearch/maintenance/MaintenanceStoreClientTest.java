package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Response;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaintenanceStoreClientTest {

    private final OpenSearchGenericClient generic = mock(OpenSearchGenericClient.class);
    private final MaintenanceStoreClient client;

    MaintenanceStoreClientTest() {
        OpenSearchClient openSearchClient = mock(OpenSearchClient.class);
        when(openSearchClient.generic()).thenReturn(generic);
        client = new MaintenanceStoreClient(openSearchClient, JsonMapper.builder().build());
    }

    // AwsSdk2Transport throws OpenSearchException instead of returning the response
    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409})
    void openSearchExceptionWithClientErrorIsReturnedAsResult(int status) throws Exception {
        when(generic.execute(any())).thenThrow(openSearchException(status, "resource_already_exists_exception"));

        MaintenanceStoreClient.Result result = client.execute("PUT", "/jobs", Map.of(), null);

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body().path("error").path("type").asString()).isEqualTo("resource_already_exists_exception");
    }

    @Test
    void wrappedOpenSearchExceptionIsUnwrapped() throws Exception {
        when(generic.execute(any())).thenThrow(new RuntimeException("wrapped", openSearchException(404, "index_not_found_exception")));

        assertThat(client.execute("GET", "/jobs/_doc/1", Map.of(), null).status()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void openSearchExceptionWithServerErrorIsUnavailable(int status) throws Exception {
        when(generic.execute(any())).thenThrow(openSearchException(status, "es_rejected_execution_exception"));

        assertThatThrownBy(() -> client.execute("GET", "/jobs/_doc/1", Map.of(), null))
                .isInstanceOf(JobStorageUnavailableException.class);
    }

    @Test
    void otherFailureIsUnavailable() throws Exception {
        when(generic.execute(any())).thenThrow(new java.io.IOException("connection refused"));

        assertThatThrownBy(() -> client.execute("GET", "/jobs/_doc/1", Map.of(), null))
                .isInstanceOf(JobStorageUnavailableException.class);
    }

    @Test
    void awsClientFailureIsUnavailable() throws Exception {
        when(generic.execute(any())).thenThrow(SdkClientException.create("Unable to execute HTTP request"));

        assertThatThrownBy(() -> client.execute("GET", "/jobs/_doc/1", Map.of(), null))
                .isInstanceOf(JobStorageUnavailableException.class);
    }

    @Test
    void programmingErrorIsNotReportedAsUnavailable() throws Exception {
        IllegalArgumentException defect = new IllegalArgumentException("bad request definition");
        when(generic.execute(any())).thenThrow(defect);

        assertThatThrownBy(() -> client.execute("GET", "/jobs/_doc/1", Map.of(), null)).isSameAs(defect);
    }

    @Test
    void nonJsonErrorBodyKeepsTheStatus() throws Exception {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(413);
        when(response.getBody()).thenReturn(Optional.of(Body.from("<html>Request Entity Too Large</html>".getBytes(), "text/html")));
        when(generic.execute(any())).thenReturn(response);

        MaintenanceStoreClient.Result result = client.execute("PUT", "/jobs/_create/1", Map.of(), null);

        assertThat(result.status()).isEqualTo(413);
        assertThat(result.body().path("error").isMissingNode()).isTrue();
    }

    private static OpenSearchException openSearchException(int status, String type) {
        return new OpenSearchException(ErrorResponse.of(r -> r.status(status).error(e -> e.type(type).reason("reason"))));
    }
}
