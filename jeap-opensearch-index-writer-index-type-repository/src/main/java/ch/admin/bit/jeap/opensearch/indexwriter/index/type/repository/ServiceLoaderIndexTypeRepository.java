package ch.admin.bit.jeap.opensearch.indexwriter.index.type.repository;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
class ServiceLoaderIndexTypeRepository implements IndexTypeRepository {

    private List<IndexType<?>> indexTypes;

    @PostConstruct
    void load() {
        load(Thread.currentThread().getContextClassLoader());
    }

    void load(ClassLoader classLoader) {
        indexTypes = List.copyOf(IndexType.loadAll(classLoader));
        if (indexTypes.isEmpty()) {
            throw ConfigurationException.noIndexTypeFound();
        }
        log.info("Loaded {} IndexType implementation(s) via ServiceLoader", indexTypes.size());
        indexTypes.forEach(t -> log.debug("  {} v{}.{}", t.originType(), t.majorVersion(), t.minorVersion()));
    }

    @Override
    @SuppressWarnings("java:S1452")
    public List<IndexType<?>> getAll() {
        return indexTypes;
    }

    @Override
    @SuppressWarnings("java:S1452")
    public Optional<IndexType<?>> findByOriginTypeAndMajorVersion(String originType, int majorVersion) {
        List<IndexType<?>> matches = indexTypes.stream()
                .filter(it -> it.originType().equals(originType) && it.majorVersion() == majorVersion)
                .toList();
        if (matches.size() > 1) {
            log.warn("Multiple IndexType implementations found for originType='{}' majorVersion={} — using highest minorVersion",
                    originType, majorVersion);
            return matches.stream().max(Comparator.comparingInt(IndexType::minorVersion));
        }
        return matches.stream().findFirst();
    }
}
