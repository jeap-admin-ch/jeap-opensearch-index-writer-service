package ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indexed;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexWriter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IndexMappingUpdater {

    private final IndexTypeRepository indexTypeRepository;
    private final IndexWriter indexWriter;

    @PostConstruct
    void updateIndexMapping() {
        List<IndexType<?>> all = indexTypeRepository.getAll();
        for (IndexType<?> indexType : all) {
            indexWriter.ensureIndexReady(indexType.indexWriteAlias(), indexType.indexReadAlias(), indexType.minorVersion(), indexType.mappingDefinition());
        }
    }
}
