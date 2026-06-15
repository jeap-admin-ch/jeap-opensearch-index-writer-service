package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.IndexTemplate;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;
import org.opensearch.client.opensearch.indices.IndexTemplateSummary;
import org.opensearch.client.opensearch.indices.put_index_template.IndexTemplateMapping;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexTemplateManagerTest {

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";
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
    void templateName_stripsWriteSuffix() {
        assertThat(IndexTemplateManager.templateName("orders_V1_write")).isEqualTo("orders_v1");
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
    void ensureIndexTemplateUpToDate_throwsException_whenTemplateDoesNotExist() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        ErrorResponse errorResponse = ErrorResponse.of(e -> e
                .status(404)
                .error(ErrorCause.of(c -> c.type("resource_not_found_exception").reason("not found"))));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class)))
                .thenThrow(new OpenSearchException(errorResponse));

        assertThatThrownBy(() -> indexTemplateManager.ensureIndexTemplateUpToDate(
                INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class)))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS)
                .hasMessageContaining("IaC");
    }

    @Test
    void ensureIndexTemplateUpToDate_updatesTemplate_whenVersionMismatch() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupExistingTemplate(1L, null);
        when(indicesClient.putIndexTemplate(any(PutIndexTemplateRequest.class)))
                .thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class));

        verify(indicesClient).putIndexTemplate(any(PutIndexTemplateRequest.class));
    }

    @Test
    void ensureIndexTemplateUpToDate_preservesIaCSettings_whenUpdatingMapping() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);

        // Simulate IaC-created template with custom settings (shards, replicas) and read alias
        IndexSettings iacSettings = IndexSettings.of(s -> s
                .numberOfShards(5)
                .numberOfReplicas(1)
                .customSettings("plugins.index_state_management.rollover_alias", JsonData.of(INDEX_WRITE_ALIAS)));
        Map<String, Alias> iacAliases = Map.of("orders_read", new Alias.Builder().build());
        IndexTemplateSummary iacTemplateSummary = IndexTemplateSummary.of(t -> t
                .settings(iacSettings)
                .aliases(iacAliases));

        setupExistingTemplate(1L, iacTemplateSummary);
        ArgumentCaptor<PutIndexTemplateRequest> putCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        when(indicesClient.putIndexTemplate(putCaptor.capture())).thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class));

        PutIndexTemplateRequest put = putCaptor.getValue();
        assertThat(put.template()).isNotNull();
        assertThat(put.template().settings()).isEqualTo(iacSettings);
        assertThat(put.template().aliases()).isEqualTo(iacAliases);
        assertThat(put.version()).isEqualTo((long) MINOR_VERSION);
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

    // --- helpers ---

    private void setupExistingTemplate(long version, IndexTemplateSummary templateMapping) throws IOException {
        IndexTemplate existingTemplate = mock(IndexTemplate.class);
        when(existingTemplate.version()).thenReturn(version);
        when(existingTemplate.indexPatterns()).thenReturn(List.of(IndexTemplateManager.indexPattern(INDEX_WRITE_ALIAS)));
        when(existingTemplate.template()).thenReturn(templateMapping);
        IndexTemplateItem templateItem = mock(IndexTemplateItem.class);
        when(templateItem.name()).thenReturn(IndexTemplateManager.templateName(INDEX_WRITE_ALIAS));
        when(templateItem.indexTemplate()).thenReturn(existingTemplate);
        GetIndexTemplateResponse templateResponse = mock(GetIndexTemplateResponse.class);
        when(templateResponse.indexTemplates()).thenReturn(List.of(templateItem));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class))).thenReturn(templateResponse);
    }
}
