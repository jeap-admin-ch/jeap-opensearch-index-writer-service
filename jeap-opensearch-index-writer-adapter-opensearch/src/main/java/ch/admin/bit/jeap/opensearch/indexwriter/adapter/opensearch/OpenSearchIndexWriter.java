package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.indices.IndexTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.function.Supplier;

@Component
@Slf4j
public class OpenSearchIndexWriter implements IndexWriter {

    @Override
    public void ensureIndexReady(String indexWriteAlias, int minorVersion, Supplier<InputStream> mappingDefinition) {
        log.debug("ensure template and mapping are ready for index '{}', minor version: {}", indexWriteAlias, minorVersion);
        // will be implemented later
        // Resolves the index using IndexWriteAlias
        // Verifies if IndexTemplate exists and is up-to-date (schema_version)
        // Creates IndexTemplate if needed
        // Verifies if mapping is set and up-to-date (schema_version)
        // Sets or updates the mapping if needed
    }

    @Override
    public void upsertSearchItem(String indexWriteAlias, String documentId, SearchItemIndexed<?> searchItem) {
        log.debug("Upsert document with ID '{}' to OpenSearch index '{}'", documentId, indexWriteAlias);
        // will be implemented later
    }

    @Override
    public void deleteSearchItem(String indexWriteAlias, String documentId) {
        log.debug("Delete document with ID '{}' from OpenSearch index '{}'", documentId, indexWriteAlias);
        // will be implemented later
    }


}
