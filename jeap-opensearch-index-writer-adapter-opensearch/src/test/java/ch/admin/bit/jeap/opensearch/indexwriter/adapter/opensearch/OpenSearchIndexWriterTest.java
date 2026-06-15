package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indextype.SearchItemMetadata;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.DeleteResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchIndexWriterTest {

    private static final String INDEX_WRITE_ALIAS = "orders_V1_write";
    private static final String INDEX_READ_ALIAS = "orders_read";
    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 3;
    private static final String MAPPING_JSON = """
            {
              "mappings": {
                "dynamic": false,
                "properties": {
                  "order_id": { "type": "keyword" }
                }
              }
            }
            """;

    @Mock
    private OpenSearchClient openSearchClient;

    @Mock
    private DataFieldValidator dataFieldValidator;

    @Mock
    private IndexTemplateManager indexTemplateManager;

    @Mock
    private PhysicalIndexManager physicalIndexManager;

    @Mock
    private IndexMappingManager indexMappingManager;

    @InjectMocks
    private OpenSearchIndexWriter indexWriter;

    // --- ensureIndexReady ---

    @Test
    void ensureIndexReady_throwsException_whenOpenSearchFails() {
        when(indexMappingManager.parseMappingWithVersion(eq(INDEX_WRITE_ALIAS), any(InputStream.class), eq(MINOR_VERSION)))
                .thenThrow(OpenSearchIndexWriterException.mappingParseFailed(INDEX_WRITE_ALIAS, new IOException("Connection refused")));

        assertThatThrownBy(() -> indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, () -> mappingStream(MAPPING_JSON)))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);
    }

    @Test
    void ensureIndexReady_logsOpenSearchErrorDetails_whenOpenSearchExceptionThrown() throws IOException {
        when(indexMappingManager.parseMappingWithVersion(eq(INDEX_WRITE_ALIAS), any(InputStream.class), eq(MINOR_VERSION)))
                .thenThrow(securityException());

        ListAppender<ILoggingEvent> logs = attachLogAppender();

        assertThatThrownBy(() -> indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, () -> mappingStream(MAPPING_JSON)))
                .isInstanceOf(OpenSearchIndexWriterException.class);

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("403")
                            .contains("security_exception")
                            .contains("no permissions for [indices:admin/template/get]");
                });
    }

    @Test
    void ensureIndexReady_delegatesToManagersInOrder() throws IOException {
        TypeMapping typeMapping = mock(TypeMapping.class);
        when(indexMappingManager.parseMappingWithVersion(eq(INDEX_WRITE_ALIAS), any(InputStream.class), eq(MINOR_VERSION)))
                .thenReturn(typeMapping);

        indexWriter.ensureIndexReady(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, () -> mappingStream(MAPPING_JSON));

        verify(indexMappingManager).parseMappingWithVersion(eq(INDEX_WRITE_ALIAS), any(InputStream.class), eq(MINOR_VERSION));
        verify(indexTemplateManager).ensureIndexTemplateUpToDate(INDEX_WRITE_ALIAS, INDEX_READ_ALIAS, MINOR_VERSION, typeMapping);
        verify(physicalIndexManager).ensureWriteIndexExists(INDEX_WRITE_ALIAS);
        verify(indexMappingManager).ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, typeMapping);
    }

    // --- upsertSearchItem ---

    @Test
    @SuppressWarnings("unchecked")
    void upsertSearchItem_indexesDocumentWithCorrectAlias() throws IOException {
        SearchItemIndexed<?> searchItem = buildSearchItem();
        when(openSearchClient.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, "doc-123", searchItem);

        ArgumentCaptor<IndexRequest<Object>> captor = ArgumentCaptor.forClass(IndexRequest.class);
        verify(openSearchClient).index(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX_WRITE_ALIAS);
        assertThat(captor.getValue().id()).isEqualTo("doc-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertSearchItem_throwsException_whenOpenSearchFails() throws IOException {
        when(openSearchClient.index(any(IndexRequest.class))).thenThrow(new IOException("write error"));

        assertThatThrownBy(() -> indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, "doc-123", buildSearchItem()))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertSearchItem_logsOpenSearchErrorDetails_whenOpenSearchExceptionThrown() throws IOException {
        when(openSearchClient.index(any(IndexRequest.class))).thenThrow(securityException());

        ListAppender<ILoggingEvent> logs = attachLogAppender();

        assertThatThrownBy(() -> indexWriter.upsertSearchItem(INDEX_WRITE_ALIAS, "doc-123", buildSearchItem()))
                .isInstanceOf(OpenSearchIndexWriterException.class);

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("403")
                            .contains("security_exception")
                            .contains("no permissions for [indices:admin/template/get]");
                });
    }

    // --- deleteSearchItem ---

    @Test
    void deleteSearchItem_deletesDocumentWithCorrectAlias() throws IOException {
        when(openSearchClient.delete(any(DeleteRequest.class))).thenReturn(mock(DeleteResponse.class));

        indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, "doc-456");

        ArgumentCaptor<DeleteRequest> captor = ArgumentCaptor.forClass(DeleteRequest.class);
        verify(openSearchClient).delete(captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(INDEX_WRITE_ALIAS);
        assertThat(captor.getValue().id()).isEqualTo("doc-456");
    }

    @Test
    void deleteSearchItem_throwsException_whenOpenSearchFails() throws IOException {
        when(openSearchClient.delete(any(DeleteRequest.class))).thenThrow(new IOException("delete error"));

        assertThatThrownBy(() -> indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, "doc-456"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);
    }

    @Test
    void deleteSearchItem_logsOpenSearchErrorDetails_whenOpenSearchExceptionThrown() throws IOException {
        when(openSearchClient.delete(any(DeleteRequest.class))).thenThrow(securityException());

        ListAppender<ILoggingEvent> logs = attachLogAppender();

        assertThatThrownBy(() -> indexWriter.deleteSearchItem(INDEX_WRITE_ALIAS, "doc-456"))
                .isInstanceOf(OpenSearchIndexWriterException.class);

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("403")
                            .contains("security_exception")
                            .contains("no permissions for [indices:admin/template/get]");
                });
    }

    // --- helpers ---

    private static ByteArrayInputStream mappingStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static SearchItemIndexed<String> buildSearchItem() {
        Origin origin = new Origin("id-1", "1", null, null, Instant.now(), Instant.now(), null);
        SearchItem<String> base = new SearchItem<>(origin, "data");
        SearchItemMetadata meta = SearchItemMetadata.initial(MAJOR_VERSION, MINOR_VERSION);
        return SearchItemIndexed.of(base, meta);
    }

    private static <T> SearchItemIndexed<T> buildSearchItemWithData(T data) {
        Origin origin = new Origin("id-1", "1", null, null, Instant.now(), Instant.now(), null);
        SearchItem<T> base = new SearchItem<>(origin, data);
        SearchItemMetadata meta = SearchItemMetadata.initial(MAJOR_VERSION, MINOR_VERSION);
        return SearchItemIndexed.of(base, meta);
    }

    private static OpenSearchException securityException() {
        ErrorResponse errorResponse = ErrorResponse.of(e -> e
                .status(403)
                .error(ErrorCause.of(ec -> ec
                        .type("security_exception")
                        .reason("no permissions for [indices:admin/template/get]"))));
        return new OpenSearchException(errorResponse);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(OpenSearchIndexWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
