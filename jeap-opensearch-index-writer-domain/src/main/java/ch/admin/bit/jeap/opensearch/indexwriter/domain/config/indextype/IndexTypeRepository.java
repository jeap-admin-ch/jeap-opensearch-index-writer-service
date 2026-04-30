package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.util.List;
import java.util.Optional;

public interface IndexTypeRepository {

    @SuppressWarnings("java:S1452")
    List<IndexType<?>> getAll();

    @SuppressWarnings("java:S1452")
    Optional<IndexType<?>> findByOriginTypeAndMajorVersion(String originType, int majorVersion);
}
