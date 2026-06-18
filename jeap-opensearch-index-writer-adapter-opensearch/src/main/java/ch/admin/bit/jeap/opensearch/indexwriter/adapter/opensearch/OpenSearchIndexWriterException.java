package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

import java.util.List;

class OpenSearchIndexWriterException extends IndexWriterException {

    private OpenSearchIndexWriterException(String message, boolean retryable, Throwable cause) {
        super(message, retryable, cause);
    }

    private OpenSearchIndexWriterException(String message, boolean retryable) {
        super(message, retryable);
    }

    static OpenSearchIndexWriterException ambiguousWriteIndex(String indexWriteAlias, java.util.Set<String> physicalIndices) {
        return new OpenSearchIndexWriterException(
                "Cannot determine physical write index for alias '%s': multiple indices %s found but none has isWriteIndex=true".formatted(indexWriteAlias, physicalIndices), false);
    }

    static OpenSearchIndexWriterException writeIndexExplicitlyDisabled(String indexWriteAlias, String physicalIndex) {
        return new OpenSearchIndexWriterException(
                ("Index '%s' has is_write_index=false for alias '%s' — likely caused by a failed ISM rollover that left the alias in a broken state. " +
                 "Fix by running: POST /_aliases {\"actions\":[{\"add\":{\"index\":\"%s\",\"alias\":\"%s\",\"is_write_index\":true}}]}")
                        .formatted(physicalIndex, indexWriteAlias, physicalIndex, indexWriteAlias), false);
    }

    static OpenSearchIndexWriterException missingTemplateSettings(String templateName) {
        return new OpenSearchIndexWriterException(
                "No index template settings configured for '%s'. Add 'jeap.opensearch.indexwriter.index-templates.%s' or a 'jeap.opensearch.indexwriter.index-templates.default' fallback entry."
                        .formatted(templateName, templateName), false);
    }

    static OpenSearchIndexWriterException incompleteTemplateSettings(String templateName, List<String> missingProperties) {
        return new OpenSearchIndexWriterException(
                "Incomplete index template settings for '%s': missing properties %s. All three must be set: number-of-shards, number-of-replicas, refresh-interval."
                        .formatted(templateName, missingProperties), false);
    }

    static OpenSearchIndexWriterException templateSetupFailed(String indexWriteAlias, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to update index template for alias '%s'".formatted(indexWriteAlias), false, cause);
    }

    static OpenSearchIndexWriterException physicalIndexSetupFailed(String indexWriteAlias, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to set up physical index for alias '%s'".formatted(indexWriteAlias), false, cause);
    }

    static OpenSearchIndexWriterException mappingParseFailed(String indexWriteAlias, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to parse mapping for alias '%s'".formatted(indexWriteAlias), false, cause);
    }

    static OpenSearchIndexWriterException mappingUpdateFailed(String indexWriteAlias, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to update mapping for alias '%s'".formatted(indexWriteAlias), false, cause);
    }

    static OpenSearchIndexWriterException ensureIndexReadyFailed(String indexWriteAlias, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to ensure index is ready for alias '%s'".formatted(indexWriteAlias), false, cause);
    }

    static OpenSearchIndexWriterException upsertFailed(String indexWriteAlias, String documentId, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to upsert document '%s' to index alias '%s'".formatted(documentId, indexWriteAlias), true, cause);
    }

    static OpenSearchIndexWriterException deleteFailed(String indexWriteAlias, String documentId, Throwable cause) {
        return new OpenSearchIndexWriterException("Failed to delete document '%s' from index alias '%s'".formatted(documentId, indexWriteAlias), true, cause);
    }

    public static OpenSearchIndexWriterException emptyMapping(String indexWriteAlias) {
        return new OpenSearchIndexWriterException("Mapping definition for index alias '%s' is empty".formatted(indexWriteAlias), false);
    }

}
