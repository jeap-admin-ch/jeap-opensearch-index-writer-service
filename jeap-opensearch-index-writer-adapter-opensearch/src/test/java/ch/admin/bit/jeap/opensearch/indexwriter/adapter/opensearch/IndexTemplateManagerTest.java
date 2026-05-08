package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.IndexTemplate;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexTemplateManagerTest {

    private static final String INDEX_WRITE_ALIAS = "orders_V1_write";
    private static final String INDEX_READ_ALIAS = "orders_read";
    private static final int MINOR_VERSION = 3;

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @InjectMocks
    private IndexTemplateManager indexTemplateManager;

    // --- templateName / indexPattern ---

    @Test
    void templateName_returnsLowercaseAliasWithTemplateSuffix() {
        assertThat(IndexTemplateManager.templateName("orders_V1_write")).isEqualTo("orders_v1_write_template");
    }

    @Test
    void indexPattern_stripsWriteSuffix_andAppendsRolloverWildcard() {
        assertThat(IndexTemplateManager.indexPattern("orders_V1_write")).isEqualTo("orders_v1-*");
    }

    @Test
    void indexPattern_withoutWriteSuffix_appendsRolloverWildcard() {
        assertThat(IndexTemplateManager.indexPattern("orders_V1")).isEqualTo("orders_v1-*");
    }

    // --- ensureIndexTemplateUpToDate ---

    @Test
    void ensureIndexTemplateUpToDate_createsTemplate_whenTemplateDoesNotExist() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        ErrorResponse errorResponse = ErrorResponse.of(e -> e
                .status(404)
                .error(ErrorCause.of(c -> c.type("resource_not_found_exception").reason("not found"))));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class)))
                .thenThrow(new OpenSearchException(errorResponse));
        when(indicesClient.putIndexTemplate(any(PutIndexTemplateRequest.class)))
                .thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class));

        verify(indicesClient).putIndexTemplate(any(PutIndexTemplateRequest.class));
    }

    @Test
    void ensureIndexTemplateUpToDate_updatesTemplate_whenVersionMismatch() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);

        IndexTemplate outdatedTemplate = mock(IndexTemplate.class);
        when(outdatedTemplate.version()).thenReturn(1L);
        IndexTemplateItem templateItem = mock(IndexTemplateItem.class);
        when(templateItem.name()).thenReturn(IndexTemplateManager.templateName(INDEX_WRITE_ALIAS));
        when(templateItem.indexTemplate()).thenReturn(outdatedTemplate);
        GetIndexTemplateResponse templateResponse = mock(GetIndexTemplateResponse.class);
        when(templateResponse.indexTemplates()).thenReturn(List.of(templateItem));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenReturn(templateResponse);
        when(indicesClient.putIndexTemplate(any(PutIndexTemplateRequest.class)))
                .thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class));

        verify(indicesClient).putIndexTemplate(any(PutIndexTemplateRequest.class));
    }

    @Test
    void ensureIndexTemplateUpToDate_skipsTemplate_whenAlreadyUpToDate() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);

        IndexTemplate currentTemplate = mock(IndexTemplate.class);
        when(currentTemplate.version()).thenReturn((long) MINOR_VERSION);
        IndexTemplateItem templateItem = mock(IndexTemplateItem.class);
        when(templateItem.name()).thenReturn(IndexTemplateManager.templateName(INDEX_WRITE_ALIAS));
        when(templateItem.indexTemplate()).thenReturn(currentTemplate);
        GetIndexTemplateResponse templateResponse = mock(GetIndexTemplateResponse.class);
        when(templateResponse.indexTemplates()).thenReturn(List.of(templateItem));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenReturn(templateResponse);

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class));

        verify(indicesClient, never()).putIndexTemplate(any(PutIndexTemplateRequest.class));
    }
}
