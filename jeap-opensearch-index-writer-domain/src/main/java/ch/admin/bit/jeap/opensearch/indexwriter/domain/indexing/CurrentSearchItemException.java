package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import lombok.Getter;

@Getter
public class CurrentSearchItemException extends IndexWriterException {

    public enum Reason {
        NOT_FOUND,
        UNSUPPORTED_INDEX_MAJOR_VERSION,
        REQUEST_REJECTED,
        RESPONSE_MISMATCH
    }

    private final Reason reason;
    private final String indexType;
    private final int indexMajorVersion;
    private final String originId;

    private CurrentSearchItemException(Reason reason, String indexType, int indexMajorVersion, String originId, String detail, Throwable cause) {
        super("%s for indexType='%s' indexMajorVersion=%d originId='%s': %s"
                .formatted(reason, indexType, indexMajorVersion, originId, detail), false, cause);
        this.reason = reason;
        this.indexType = indexType;
        this.indexMajorVersion = indexMajorVersion;
        this.originId = originId;
    }

    public static CurrentSearchItemException notFound(String indexType, int indexMajorVersion, String originId) {
        return new CurrentSearchItemException(Reason.NOT_FOUND, indexType, indexMajorVersion, originId,
                "the provider has no object with this id", null);
    }

    public static CurrentSearchItemException unsupportedIndexMajorVersion(String indexType, int indexMajorVersion, String originId) {
        return new CurrentSearchItemException(Reason.UNSUPPORTED_INDEX_MAJOR_VERSION, indexType, indexMajorVersion, originId,
                "the provider does not support this schema major", null);
    }

    public static CurrentSearchItemException requestRejected(String indexType, int indexMajorVersion, String originId, int status, Throwable cause) {
        return new CurrentSearchItemException(Reason.REQUEST_REJECTED, indexType, indexMajorVersion, originId,
                "the provider rejected the request with status %d".formatted(status), cause);
    }

    public static CurrentSearchItemException mismatch(String indexType, int indexMajorVersion, String originId, String detail) {
        return mismatch(indexType, indexMajorVersion, originId, detail, null);
    }

    public static CurrentSearchItemException mismatch(String indexType, int indexMajorVersion, String originId, String detail, Throwable cause) {
        return new CurrentSearchItemException(Reason.RESPONSE_MISMATCH, indexType, indexMajorVersion, originId, detail, cause);
    }
}
