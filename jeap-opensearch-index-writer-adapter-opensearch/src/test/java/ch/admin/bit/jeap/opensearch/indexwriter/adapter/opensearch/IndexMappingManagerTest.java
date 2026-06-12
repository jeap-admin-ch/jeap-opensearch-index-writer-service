package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.DynamicMapping;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.AliasDefinition;
import org.opensearch.client.opensearch.indices.GetAliasRequest;
import org.opensearch.client.opensearch.indices.GetAliasResponse;
import org.opensearch.client.opensearch.indices.GetMappingRequest;
import org.opensearch.client.opensearch.indices.GetMappingResponse;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsResponse;
import org.opensearch.client.opensearch.indices.PutMappingRequest;
import org.opensearch.client.opensearch.indices.PutMappingResponse;
import org.opensearch.client.opensearch.indices.get_alias.IndexAliases;
import org.opensearch.client.opensearch.indices.get_mapping.IndexMappingRecord;
import org.opensearch.client.transport.OpenSearchTransport;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexMappingManagerTest {

    private static final String INDEX_WRITE_ALIAS = "orders_V1_write";
    private static final String PHYSICAL_INDEX = "orders_v1_write-000001";
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
    private OpenSearchTransport transport;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @Mock
    @SuppressWarnings("unused")
    private DataFieldValidator dataFieldValidator;

    @InjectMocks
    private IndexMappingManager indexMappingManager;

    // --- parseMappingWithVersion ---

    @Test
    void parseMappingWithVersion_throwsEmptyMappingException_whenInputStreamIsEmpty() {
        when(openSearchClient._transport()).thenReturn(transport);
        when(transport.jsonpMapper()).thenReturn(new JacksonJsonpMapper());

        assertThatThrownBy(() -> indexMappingManager.parseMappingWithVersion(INDEX_WRITE_ALIAS, InputStream.nullInputStream(), MINOR_VERSION))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);
    }

    @Test
    void parseMappingWithVersion_injectsSchemaVersionIntoMeta() throws IOException {
        setupTransportWithMapper();

        TypeMapping result = indexMappingManager.parseMappingWithVersion(INDEX_WRITE_ALIAS, mappingStream(MAPPING_JSON), MINOR_VERSION);

        assertThat(result.meta()).containsKey(IndexMappingManager.SCHEMA_VERSION_META_KEY);
        JsonpMapper mapper = new JacksonJsonpMapper();
        String version = result.meta().get(IndexMappingManager.SCHEMA_VERSION_META_KEY).to(String.class, mapper);
        assertThat(version).isEqualTo(String.valueOf(MINOR_VERSION));
    }

    // --- ensureMappingUpToDate ---

    @Test
    void ensureMappingUpToDate_throwsException_whenAliasDoesNotExist() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        when(indicesClient.getAlias(any(GetAliasRequest.class)))
                .thenThrow(new OpenSearchException(ErrorResponse.of(r -> r
                        .status(404)
                        .error(e -> e.type("alias_missing_exception").reason("no such alias")))));

        TypeMapping mapping = mock(TypeMapping.class);
        assertThatThrownBy(() -> indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);

        verify(indicesClient, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void ensureMappingUpToDate_updatesMapping_whenSchemaVersionIsOutdated() throws IOException {
        setupTransportWithMapper();
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(mock(PutIndicesSettingsResponse.class));

        TypeMapping outdatedMapping = TypeMapping.of(t -> t
                .dynamic(DynamicMapping.False)
                .meta(IndexMappingManager.SCHEMA_VERSION_META_KEY, JsonData.of("1")));
        setupMappingForIndex(PHYSICAL_INDEX, outdatedMapping);

        when(indicesClient.putMapping(any(PutMappingRequest.class)))
                .thenReturn(mock(PutMappingResponse.class));

        indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, outdatedMapping);

        verify(indicesClient).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void ensureMappingUpToDate_skipsMapping_whenSchemaVersionIsUpToDate() throws IOException {
        setupTransportWithMapper();
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(mock(PutIndicesSettingsResponse.class));

        TypeMapping currentMapping = TypeMapping.of(t -> t
                .dynamic(DynamicMapping.False)
                .meta(IndexMappingManager.SCHEMA_VERSION_META_KEY, JsonData.of(String.valueOf(MINOR_VERSION))));
        setupMappingForIndex(PHYSICAL_INDEX, currentMapping);

        indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, currentMapping);

        verify(indicesClient, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void ensureMappingUpToDate_setsRolloverAlias_onPhysicalIndex() throws IOException {
        setupTransportWithMapper();
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);

        TypeMapping currentMapping = TypeMapping.of(t -> t
                .dynamic(DynamicMapping.False)
                .meta(IndexMappingManager.SCHEMA_VERSION_META_KEY, JsonData.of(String.valueOf(MINOR_VERSION))));
        setupMappingForIndex(PHYSICAL_INDEX, currentMapping);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(mock(PutIndicesSettingsResponse.class));

        indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, currentMapping);

        verify(indicesClient).putSettings(any(PutIndicesSettingsRequest.class));
    }

    // --- resolvePhysicalWriteIndex ---

    @Test
    void ensureMappingUpToDate_selectsWriteIndex_whenMultiplePhysicalIndicesExist() throws IOException {
        setupTransportWithMapper();
        when(openSearchClient.indices()).thenReturn(indicesClient);

        String oldIndex = "orders_v1-000001";
        String writeIndex = "orders_v1-000002";

        IndexAliases writeIndexAliases = mock(IndexAliases.class);
        when(writeIndexAliases.aliases()).thenReturn(
                Map.of(INDEX_WRITE_ALIAS, AliasDefinition.of(a -> a.isWriteIndex(true))));

        // oldIndexAliases has no isWriteIndex designation — Mockito default returns empty Map for aliases()
        IndexAliases oldIndexAliases = mock(IndexAliases.class);

        GetAliasResponse aliasResponse = mock(GetAliasResponse.class);
        when(aliasResponse.result()).thenReturn(Map.of(writeIndex, writeIndexAliases, oldIndex, oldIndexAliases));
        when(indicesClient.getAlias(any(GetAliasRequest.class))).thenReturn(aliasResponse);

        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(mock(PutIndicesSettingsResponse.class));

        TypeMapping currentMapping = TypeMapping.of(t -> t
                .meta(IndexMappingManager.SCHEMA_VERSION_META_KEY, JsonData.of(String.valueOf(MINOR_VERSION))));
        setupMappingForIndex(writeIndex, currentMapping);

        indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, currentMapping);

        // putSettings called exactly once — only for the designated write index, not the old one
        verify(indicesClient).putSettings(any(PutIndicesSettingsRequest.class));
        verify(indicesClient, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void ensureMappingUpToDate_throwsException_whenMultipleIndicesExistWithoutWriteIndexDesignation() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);

        IndexAliases noDesignation = mock(IndexAliases.class);
        when(noDesignation.aliases()).thenReturn(Map.of(INDEX_WRITE_ALIAS, AliasDefinition.of(a -> a)));
        GetAliasResponse aliasResponse = mock(GetAliasResponse.class);
        when(aliasResponse.result()).thenReturn(
                Map.of("orders_v1-000001", noDesignation, "orders_v1-000002", noDesignation));
        when(indicesClient.getAlias(any(GetAliasRequest.class))).thenReturn(aliasResponse);

        TypeMapping mapping = mock(TypeMapping.class);
        assertThatThrownBy(() -> indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining(INDEX_WRITE_ALIAS);
    }

    // --- cluster_block_exception handling ---

    @Test
    void ensureMappingUpToDate_logsWarningAndDoesNotThrow_whenClusterBlockExceptionOnPutSettings() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenThrow(clusterBlockException());

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        TypeMapping mapping = mock(TypeMapping.class);

        assertThatNoException().isThrownBy(() ->
                indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping));

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(PHYSICAL_INDEX)
                            .contains(INDEX_WRITE_ALIAS);
                });
        verify(indicesClient, never()).putMapping(any(PutMappingRequest.class));
    }

    @Test
    void ensureMappingUpToDate_logsWarningAndDoesNotThrow_whenClusterBlockExceptionOnPutMapping() throws IOException {
        setupTransportWithMapper();
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(mock(PutIndicesSettingsResponse.class));

        TypeMapping outdatedMapping = TypeMapping.of(t -> t
                .meta(IndexMappingManager.SCHEMA_VERSION_META_KEY, JsonData.of("0")));
        setupMappingForIndex(PHYSICAL_INDEX, outdatedMapping);
        when(indicesClient.putMapping(any(PutMappingRequest.class)))
                .thenThrow(clusterBlockException());

        ListAppender<ILoggingEvent> logs = attachLogAppender();

        assertThatNoException().isThrownBy(() ->
                indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, outdatedMapping));

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(PHYSICAL_INDEX)
                            .contains(INDEX_WRITE_ALIAS);
                });
    }

    @Test
    void ensureMappingUpToDate_logsWarningAndDoesNotThrow_whenClusterBlockExceptionSurfacedAsIOException() throws IOException {
        // The HTTP transport (ApacheHttpClient5Transport) can throw ResponseException (an IOException)
        // instead of OpenSearchException for cluster_block errors.
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenThrow(new IOException("cluster_block_exception: index has read-only-allow-delete block"));

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        TypeMapping mapping = mock(TypeMapping.class);

        assertThatNoException().isThrownBy(() ->
                indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping));

        assertThat(logs.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(PHYSICAL_INDEX)
                            .contains(INDEX_WRITE_ALIAS);
                });
    }

    @Test
    void ensureMappingUpToDate_propagatesNonClusterBlockOpenSearchExceptions() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenThrow(securityException());

        TypeMapping mapping = mock(TypeMapping.class);
        assertThatThrownBy(() -> indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping))
                .isInstanceOf(OpenSearchException.class);
    }

    @Test
    void ensureMappingUpToDate_propagatesNonClusterBlockIOExceptions() throws IOException {
        when(openSearchClient.indices()).thenReturn(indicesClient);
        setupAliasWithPhysicalIndex(PHYSICAL_INDEX);
        when(indicesClient.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenThrow(new IOException("connection refused"));

        TypeMapping mapping = mock(TypeMapping.class);
        assertThatThrownBy(() -> indexMappingManager.ensureMappingUpToDate(INDEX_WRITE_ALIAS, MINOR_VERSION, mapping))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("connection refused");
    }

    // --- helpers ---

    private void setupTransportWithMapper() {
        JsonpMapper mapper = new JacksonJsonpMapper();
        when(openSearchClient._transport()).thenReturn(transport);
        when(transport.jsonpMapper()).thenReturn(mapper);
    }

    private void setupAliasWithPhysicalIndex(String physicalIndex) throws IOException {
        GetAliasResponse aliasResponse = mock(GetAliasResponse.class);
        when(aliasResponse.result()).thenReturn(Map.of(physicalIndex, mock(
                org.opensearch.client.opensearch.indices.get_alias.IndexAliases.class)));
        when(indicesClient.getAlias(any(GetAliasRequest.class))).thenReturn(aliasResponse);
    }

    private void setupMappingForIndex(String indexName, TypeMapping typeMapping) throws IOException {
        IndexMappingRecord record = mock(IndexMappingRecord.class);
        when(record.mappings()).thenReturn(typeMapping);
        GetMappingResponse mappingResponse = mock(GetMappingResponse.class);
        when(mappingResponse.result()).thenReturn(Map.of(indexName, record));
        when(indicesClient.getMapping(any(GetMappingRequest.class))).thenReturn(mappingResponse);
    }

    private static InputStream mappingStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static OpenSearchException clusterBlockException() {
        return new OpenSearchException(ErrorResponse.of(r -> r
                .status(429)
                .error(e -> e
                        .type("cluster_block_exception")
                        .reason("index [" + PHYSICAL_INDEX + "] blocked by: [TOO_MANY_REQUESTS/12/disk usage exceeded flood-stage watermark]"))));
    }

    private static OpenSearchException securityException() {
        return new OpenSearchException(ErrorResponse.of(r -> r
                .status(403)
                .error(e -> e.type("security_exception").reason("access denied"))));
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(IndexMappingManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
