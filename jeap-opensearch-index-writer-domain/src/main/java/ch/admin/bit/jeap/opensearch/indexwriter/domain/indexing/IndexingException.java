package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;

public class IndexingException extends IndexWriterException {

    private IndexingException(String message, boolean retryable) {
        super(message, retryable);
    }

    private IndexingException(String message, boolean retryable, Throwable cause) {
        super(message, retryable, cause);
    }

    public static IndexingException noIndexWriterConfigFound(String typeName) {
        return new IndexingException("No index writer configuration found for message type '" + typeName + "'", true);
    }

    public static IndexingException searchItemNotFound(MessageOperationConfig operation, OriginReference originReference) {
        return new IndexingException("No search item found for indexType='" + operation.indexType() + "' originId='" + originReference.id() + "'", true);
    }

    public static IndexingException indexTypeNotFound(String indexType, int indexMajorVersion) {
        return new IndexingException("No index type found for indexType='" + indexType + "' and major version '" + indexMajorVersion + "'", true);
    }

    public static IndexingException searchItemProviderServerError(String baseUri, String indexType, int statusCode) {
        return new IndexingException("Search item provider returned server error " + statusCode + " for baseUri='" + baseUri + "' indexType='" + indexType + "'", true);
    }

    public static IndexingException searchItemProviderNotReachable(String baseUri, String indexType, Throwable cause) {
        return new IndexingException("Search item provider not reachable at baseUri='" + baseUri + "' indexType='" + indexType + "'", true, cause);
    }
}
