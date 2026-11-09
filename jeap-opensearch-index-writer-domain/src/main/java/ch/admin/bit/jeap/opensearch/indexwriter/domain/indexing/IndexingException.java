package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.reference.OriginReference;

import java.util.List;

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
        return new IndexingException("Search item provider returned server error " + statusCode + " for " + provider(baseUri, indexType), true);
    }

    public static IndexingException searchItemProviderTemporarilyUnavailable(String baseUri, String indexType, int statusCode) {
        return new IndexingException("Search item provider returned temporary error " + statusCode + " for " + provider(baseUri, indexType), true);
    }

    public static IndexingException searchItemProviderNotReachable(String baseUri, String indexType, Throwable cause) {
        return new IndexingException("Search item provider not reachable at " + provider(baseUri, indexType), true, cause);
    }

    public static IndexingException searchItemProviderAuthorizationFailed(String baseUri, String indexType, String oauthClientId, Throwable cause) {
        return new IndexingException("Failed to obtain an OAuth token for client '%s' to call search item provider at %s"
                .formatted(oauthClientId, provider(baseUri, indexType)), true, cause);
    }

    public static IndexingException oauthClientNotRegistered(String oauthClientId, Throwable cause) {
        return new IndexingException("No OAuth client registration found for client '%s'".formatted(oauthClientId), false, cause);
    }

    private static String provider(String baseUri, String indexType) {
        return "baseUri='%s' indexType='%s'".formatted(baseUri, indexType);
    }

    public static IndexingException missingRequiredFields(String indexWriteAlias, String documentId, List<String> missingFields) {
        return new IndexingException("Document '%s' for index alias '%s' is missing required fields: %s".formatted(documentId, indexWriteAlias, missingFields), false);
    }

    public static IndexingException missingResponseHeader(String headerName) {
        return new IndexingException("Missing required response header '%s'".formatted(headerName), false);
    }

    public static IndexingException dataDeserializationFailed(Class<?> dataClass, String indexType, Throwable cause) {
        return new IndexingException(
                "Failed to deserialize data into '%s' for index type '%s'. Ensure the resource service returns field names matching the data class."
                        .formatted(dataClass.getSimpleName(), indexType), false, cause);
    }
}
