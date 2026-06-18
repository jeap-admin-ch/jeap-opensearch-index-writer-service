package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indexed;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettingsProvider;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexMappingUpdaterTest {

    @Mock
    private IndexTypeRepository indexTypeRepository;

    @Mock
    private IndexWriter indexWriter;

    @Mock
    private IndexTemplateSettingsProvider templateSettingsProvider;

    @InjectMocks
    private IndexMappingUpdater indexMappingUpdater;

    private static final IndexTemplateSettings DEFAULT_SETTINGS = new IndexTemplateSettings(1, 1, "1s");

    @Test
    void updateIndexMapping_noIndexTypes_noWriterInteraction() {
        when(indexTypeRepository.getAll()).thenReturn(List.of());

        indexMappingUpdater.updateIndexMapping();

        verifyNoInteractions(indexWriter);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void updateIndexMapping_singleIndexType_callsUpsertWithCorrectArguments() {
        Supplier<InputStream> mappingDefinition = mock(Supplier.class);
        IndexType indexType = stubIndexType("my-index-write", 3, mappingDefinition);
        when(indexTypeRepository.getAll()).thenReturn(List.of(indexType));
        when(templateSettingsProvider.getSettings("my-index-write")).thenReturn(DEFAULT_SETTINGS);

        indexMappingUpdater.updateIndexMapping();

        verify(indexWriter).ensureIndexReady("my-index-write", "my-index-read", 3, mappingDefinition, DEFAULT_SETTINGS);
        verifyNoMoreInteractions(indexWriter);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void updateIndexMapping_multipleIndexTypes_callsUpsertForEach() {
        Supplier<InputStream> mapping1 = mock(Supplier.class);
        Supplier<InputStream> mapping2 = mock(Supplier.class);
        IndexType indexType1 = stubIndexType("index-one-write", 1, mapping1);
        IndexType indexType2 = stubIndexType("index-two-write", 2, mapping2);
        when(indexTypeRepository.getAll()).thenReturn(List.of(indexType1, indexType2));
        when(templateSettingsProvider.getSettings("index-one-write")).thenReturn(DEFAULT_SETTINGS);
        when(templateSettingsProvider.getSettings("index-two-write")).thenReturn(DEFAULT_SETTINGS);

        indexMappingUpdater.updateIndexMapping();

        verify(indexWriter).ensureIndexReady("index-one-write", "index-one-read", 1, mapping1, DEFAULT_SETTINGS);
        verify(indexWriter).ensureIndexReady("index-two-write", "index-two-read", 2, mapping2, DEFAULT_SETTINGS);
        verifyNoMoreInteractions(indexWriter);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IndexType stubIndexType(String writeAlias, int minorVersion, Supplier<InputStream> mappingDefinition) {
        IndexType indexType = mock(IndexType.class);
        when(indexType.indexWriteAlias()).thenReturn(writeAlias);
        when(indexType.indexReadAlias()).thenReturn(writeAlias.replace("-write", "-read"));
        when(indexType.minorVersion()).thenReturn(minorVersion);
        when(indexType.mappingDefinition()).thenReturn(mappingDefinition);
        return indexType;
    }
}
