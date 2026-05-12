package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import lombok.Getter;


@Getter
public class IndexTypeAccessDeniedException extends RuntimeException {

    private final transient IndexType<?> indexType;

    private IndexTypeAccessDeniedException(IndexType<?> indexType, String message) {
        super(message);
        this.indexType = indexType;
    }

    public static IndexTypeAccessDeniedException noAuthorization(IndexType<?> indexType) {
        return new IndexTypeAccessDeniedException(indexType,
                "Access denied to index type '" + indexType.getClass().getSimpleName() + "': no authorization available.");
    }

    public static IndexTypeAccessDeniedException notAuthorized(IndexType<?> indexType) {
        return new IndexTypeAccessDeniedException(indexType,
                "Access denied to index type '" + indexType.getClass().getSimpleName() + "': none of the required roles present");
    }

}
