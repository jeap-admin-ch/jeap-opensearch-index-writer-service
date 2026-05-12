package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.springframework.lang.Nullable;

import java.util.Objects;

public class IndexTypeAuthorization {

    public boolean canAccess(IndexType<?> indexType, @Nullable Authorization auth) {
        Objects.requireNonNull(indexType, "indexType must not be null");
        if (auth == null) {
            return false;
        }
        return indexType.roles().stream().anyMatch(auth.allRoles()::contains);
    }

    public void checkAccess(IndexType<?> indexType, @Nullable Authorization auth) {
        Objects.requireNonNull(indexType, "indexType must not be null");
        if (auth == null) {
            throw IndexTypeAccessDeniedException.noAuthorization(indexType);
        }
        if (!canAccess(indexType, auth)) {
            throw IndexTypeAccessDeniedException.notAuthorized(indexType);
        }
    }
}
