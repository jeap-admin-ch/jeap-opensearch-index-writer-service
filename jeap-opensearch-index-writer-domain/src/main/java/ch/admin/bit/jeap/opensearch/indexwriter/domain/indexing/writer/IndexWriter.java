package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;

import java.io.InputStream;
import java.util.function.Supplier;

public interface IndexWriter {

    void ensureIndexReady(String indexWriteAlias, String indexReadAlias, int minorVersion, Supplier<InputStream> mappingDefinition);

    @SuppressWarnings("java:S1452")
    void upsertSearchItem(String indexWriteAlias, String documentId, SearchItemIndexed<?> searchItem);

    void deleteSearchItem(String indexWriteAlias, String documentId);
}
