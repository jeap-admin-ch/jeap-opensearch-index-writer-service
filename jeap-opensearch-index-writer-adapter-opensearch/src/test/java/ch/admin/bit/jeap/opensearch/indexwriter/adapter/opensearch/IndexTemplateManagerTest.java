package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.PutIndexTemplateResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexTemplateManagerTest {

    private static final String INDEX_WRITE_ALIAS = "orders_v1_write";
    private static final String INDEX_READ_ALIAS = "orders_read";
    private static final int MINOR_VERSION = 3;
    private static final IndexTemplateSettings TEMPLATE_SETTINGS = new IndexTemplateSettings(2, 1, "5s");

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @InjectMocks
    private IndexTemplateManager indexTemplateManager;

    @Test
    void ensureIndexTemplateUpToDate_alwaysPutsTemplate() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.putIndexTemplate(any(PutIndexTemplateRequest.class)))
                .thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class), TEMPLATE_SETTINGS);

        verify(indicesClient).putIndexTemplate(any(PutIndexTemplateRequest.class));
    }

    @Test
    void ensureIndexTemplateUpToDate_putsTemplateWithCorrectSettings() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        ArgumentCaptor<PutIndexTemplateRequest> putCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        when(indicesClient.putIndexTemplate(putCaptor.capture())).thenReturn(mock(PutIndexTemplateResponse.class));

        indexTemplateManager.ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, mock(TypeMapping.class), TEMPLATE_SETTINGS);

        PutIndexTemplateRequest put = putCaptor.getValue();
        assertThat(put.name()).isEqualTo(IndexNaming.logicalName(INDEX_WRITE_ALIAS));
        assertThat(put.indexPatterns()).containsExactly(IndexNaming.indexPattern(INDEX_WRITE_ALIAS));
        assertThat(put.version()).isEqualTo((long) MINOR_VERSION);
        assertThat(put.template().aliases()).containsKey(INDEX_READ_ALIAS);
        assertThat(put.template().aliases()).doesNotContainKey(INDEX_WRITE_ALIAS);
        assertThat(put.template().settings().numberOfShards()).isEqualTo(TEMPLATE_SETTINGS.getNumberOfShards());
        assertThat(put.template().settings().numberOfReplicas()).isEqualTo(TEMPLATE_SETTINGS.getNumberOfReplicas());
        assertThat(put.template().settings().refreshInterval().time()).isEqualTo(TEMPLATE_SETTINGS.getRefreshInterval());
    }
}
