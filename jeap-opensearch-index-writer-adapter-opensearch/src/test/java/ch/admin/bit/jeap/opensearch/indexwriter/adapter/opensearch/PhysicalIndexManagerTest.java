package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.GetAliasRequest;
import org.opensearch.client.opensearch.indices.GetAliasResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhysicalIndexManagerTest {

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @InjectMocks
    private PhysicalIndexManager physicalIndexManager;

    @Test
    void ensureWriteIndexExists_doesNothing_whenAliasAlreadyExists() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.getAlias(any(GetAliasRequest.class))).thenReturn(mock(GetAliasResponse.class));

        physicalIndexManager.ensureWriteIndexExists(INDEX_WRITE_ALIAS);

        verify(indicesClient, never()).create(any(CreateIndexRequest.class));
    }

    @Test
    void ensureWriteIndexExists_createsPhysicalIndex_whenAliasDoesNotExist() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.getAlias(any(GetAliasRequest.class)))
                .thenThrow(new OpenSearchException(ErrorResponse.of(r -> r
                        .status(404)
                        .error(e -> e.type("alias_missing_exception").reason("no such alias")))));

        ArgumentCaptor<CreateIndexRequest> createCaptor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        when(indicesClient.create(createCaptor.capture())).thenReturn(mock(CreateIndexResponse.class));

        physicalIndexManager.ensureWriteIndexExists(INDEX_WRITE_ALIAS);

        // "orders_v1_write" → strip "_write" → "orders_v1" → "orders_v1-000001"
        // Write alias is set explicitly so ISM manages it exclusively (not via template).
        // Read alias and ISM rollover alias setting come from the IaC-created index template.
        CreateIndexRequest request = createCaptor.getValue();
        assertThat(request.index()).isEqualTo("orders_v1-000001");
        assertThat(request.aliases()).containsKey(INDEX_WRITE_ALIAS);
        Alias writeAlias = request.aliases().get(INDEX_WRITE_ALIAS);
        assertThat(writeAlias.isWriteIndex()).isTrue();
    }

    @Test
    void ensureWriteIndexExists_propagatesNon404Exceptions() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.getAlias(any(GetAliasRequest.class)))
                .thenThrow(new OpenSearchException(ErrorResponse.of(r -> r
                        .status(403)
                        .error(e -> e.type("security_exception").reason("access denied")))));

        assertThatThrownBy(() -> physicalIndexManager.ensureWriteIndexExists(INDEX_WRITE_ALIAS))
                .isInstanceOf(OpenSearchException.class);
    }
}
