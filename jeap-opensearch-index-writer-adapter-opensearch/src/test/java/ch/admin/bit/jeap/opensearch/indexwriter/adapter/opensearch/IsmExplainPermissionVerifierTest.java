package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Response;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsmExplainPermissionVerifierTest {

    @Mock
    private OpenSearchClient client;
    @Mock
    private OpenSearchGenericClient genericClient;
    @Mock
    private Response response;

    private IsmExplainPermissionVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new IsmExplainPermissionVerifier(client);
        when(client.generic()).thenReturn(genericClient);
    }

    @Test
    void permittedExplainIsVerified() throws IOException {
        when(genericClient.execute(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(400);

        assertThat(verifier.verify()).isTrue();
    }

    @Test
    void forbiddenResponseIsDetected() throws IOException {
        when(genericClient.execute(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(403);

        assertThat(verifier.verify()).isFalse();
    }

    @Test
    void awsForbiddenIsDetectedWithoutFailingStartup() throws IOException {
        when(genericClient.execute(any())).thenThrow(IsmAppliedPolicyInspectorTest.awsForbidden());

        assertThat(verifier.verify()).isFalse();
        assertThatCode(verifier::verifyOnStartup).doesNotThrowAnyException();
    }

    @Test
    void unexpectedFailureDoesNotFailStartup() throws IOException {
        when(genericClient.execute(any())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(verifier::verifyOnStartup).doesNotThrowAnyException();
        assertThat(verifier.verify()).isTrue();
    }

    @Test
    void connectionFailureDoesNotFailStartup() throws IOException {
        when(genericClient.execute(any())).thenThrow(new IOException("Connection refused"));

        assertThat(verifier.verify()).isTrue();
    }
}
