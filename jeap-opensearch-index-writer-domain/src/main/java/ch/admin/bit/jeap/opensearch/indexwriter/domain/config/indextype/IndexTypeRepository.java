package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype;

import ch.admin.bit.jeap.opensearch.indextype.IndexTypeDescriptor;

import java.util.List;
import java.util.Optional;

public interface IndexTypeRepository {

    List<IndexTypeDescriptor> getAll();

    Optional<IndexTypeDescriptor> findByOriginTypeAndMajorVersion(String originType, int majorVersion);
}
