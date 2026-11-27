package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.MaintenanceTargetWriter;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TargetWriteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.TaskErrorCode;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.transport.httpclient5.ResponseException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
class OpenSearchMaintenanceTargetWriter implements MaintenanceTargetWriter {

    private static final String INDEX_NOT_FOUND = "index_not_found_exception";
    private static final String CLUSTER_BLOCK = "cluster_block_exception";

    private final OpenSearchClient openSearchClient;
    private final MaintenanceStoreClient client;

    @Override
    public Optional<String> findIndexUuid(String physicalIndex) {
        MaintenanceStoreClient.Result result;
        try {
            result = client.execute("GET", "/" + physicalIndex + "/_settings/index.uuid", Map.of(), null);
        } catch (JobStorageUnavailableException e) {
            throw TargetWriteException.unavailable("Could not check target index %s: %s".formatted(physicalIndex, e.getMessage()), e);
        }
        if (result.status() == 404) {
            return Optional.empty();
        }
        if (!result.ok()) {
            throw TargetWriteException.permanent(code(result.status(), result.body().path("error").path("type").asString("")),
                    "Could not check target index %s: HTTP %d %s".formatted(physicalIndex, result.status(), result.body()), null);
        }
        JsonNode uuid = result.body().path(physicalIndex).path("settings").path("index").path("uuid");
        return Optional.ofNullable(uuid.isString() ? uuid.stringValue() : null);
    }

    // The typed client serialises the document exactly like event-driven indexing does
    @Override
    public void upsert(String physicalIndex, String documentId, SearchItemIndexed<?> searchItem) {
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            IndexRequest<Object> request = new IndexRequest.Builder()
                    .index(physicalIndex)
                    .id(documentId)
                    .document(searchItem)
                    .build();
            openSearchClient.index(request);
        } catch (Exception e) {
            throw translate(physicalIndex, documentId, e);
        }
    }

    private static TargetWriteException translate(String physicalIndex, String documentId, Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OpenSearchException o) {
                ErrorCause error = o.error();
                String type = error == null ? "" : error.type();
                String reason = error == null ? "" : error.reason();
                return failure(physicalIndex, documentId, o.status(), type, "%s: %s".formatted(type, reason), e);
            }
            if (t instanceof ResponseException r) {
                return failure(physicalIndex, documentId, r.status(), "", r.getMessage(), e);
            }
        }
        if (isTransportFailure(e)) {
            return TargetWriteException.unavailable("Writing document %s to %s failed: %s".formatted(documentId, physicalIndex, e), e);
        }
        // Anything else (e.g. the document cannot be serialised) is a defect: retrying cannot help and must not hide it
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException("Writing document %s to %s failed unexpectedly".formatted(documentId, physicalIndex), e);
    }

    // ApacheHttpClient5Transport reports connection problems as IOException, AwsSdk2Transport as SdkClientException
    private static boolean isTransportFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof SdkClientException) {
                return true;
            }
        }
        return false;
    }

    private static TargetWriteException failure(String physicalIndex, String documentId, int status, String type, String detail, Exception cause) {
        String message = "OpenSearch answered HTTP %d for document %s in %s: %s".formatted(status, documentId, physicalIndex, detail);
        if (status == 429 || status >= 500) {
            return TargetWriteException.unavailable(message, cause);
        }
        log.warn("Maintenance write rejected: {}", message);
        return TargetWriteException.permanent(code(status, type), message, cause);
    }

    private static TaskErrorCode code(int status, String type) {
        if (INDEX_NOT_FOUND.equals(type)) {
            return TaskErrorCode.TARGET_INDEX_MISSING;
        }
        if (status == 403 || CLUSTER_BLOCK.equals(type)) {
            return TaskErrorCode.INDEX_NOT_WRITABLE;
        }
        return TaskErrorCode.WRITE_REJECTED;
    }
}
