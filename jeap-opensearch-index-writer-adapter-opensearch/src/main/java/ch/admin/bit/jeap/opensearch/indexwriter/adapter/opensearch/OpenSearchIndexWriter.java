package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenSearchIndexWriter implements IndexWriter {

    private final OpenSearchClient openSearchClient;
    private final DataFieldValidator dataFieldValidator;
    private final IndexTemplateManager indexTemplateManager;
    private final PhysicalIndexManager physicalIndexManager;
    private final IndexMappingManager indexMappingManager;

    @Override
    public void ensureIndexReady(String indexWriteAlias, String indexReadAlias, int minorVersion, Supplier<InputStream> mappingDefinition) {
        log.debug("Ensuring index, template and mapping are ready for index '{}', minor version: {}", indexWriteAlias, minorVersion);
        try {
            TypeMapping typeMapping = indexMappingManager.parseMappingWithVersion(indexWriteAlias, mappingDefinition.get(), minorVersion);
            indexTemplateManager.ensureIndexTemplateUpToDate(indexWriteAlias, indexReadAlias, minorVersion, typeMapping);
            physicalIndexManager.ensureWriteIndexExists(indexWriteAlias);
            indexMappingManager.ensureMappingUpToDate(indexWriteAlias, minorVersion, typeMapping);
        } catch (OpenSearchException e) {
            log.error("OpenSearch error while ensuring index '{}' is ready: HTTP {}, type '{}', reason: {}",
                    indexWriteAlias, e.status(), e.response().error().type(), e.response().error().reason());
            throw OpenSearchIndexWriterException.ensureIndexReadyFailed(indexWriteAlias, e);
        }
    }

    @Override
    public void upsertSearchItem(String indexWriteAlias, String documentId, SearchItemIndexed<?> searchItem) {
        log.debug("Upserting document with ID '{}' to OpenSearch index '{}'", documentId, indexWriteAlias);
        dataFieldValidator.validateDataFields(indexWriteAlias, documentId, searchItem);
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            IndexRequest<Object> request = new IndexRequest.Builder()
                    .index(indexWriteAlias)
                    .id(documentId)
                    .document(searchItem)
                    .build();
            openSearchClient.index(request);
        } catch (OpenSearchException e) {
            log.error("OpenSearch error while upserting document '{}' to index '{}': HTTP {}, type '{}', reason: {}",
                    documentId, indexWriteAlias, e.status(), e.response().error().type(), e.response().error().reason());
            throw OpenSearchIndexWriterException.upsertFailed(indexWriteAlias, documentId, e);
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.upsertFailed(indexWriteAlias, documentId, e);
        }
    }

    @Override
    public void deleteSearchItem(String indexWriteAlias, String documentId) {
        log.debug("Deleting document with ID '{}' from OpenSearch index '{}'", documentId, indexWriteAlias);
        try {
            DeleteRequest request = new DeleteRequest.Builder()
                    .index(indexWriteAlias)
                    .id(documentId)
                    .build();
            openSearchClient.delete(request);
        } catch (OpenSearchException e) {
            log.error("OpenSearch error while deleting document '{}' from index '{}': HTTP {}, type '{}', reason: {}",
                    documentId, indexWriteAlias, e.status(), e.response().error().type(), e.response().error().reason());
            throw OpenSearchIndexWriterException.deleteFailed(indexWriteAlias, documentId, e);
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.deleteFailed(indexWriteAlias, documentId, e);
        }
    }
}
