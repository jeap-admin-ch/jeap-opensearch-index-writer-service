package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class IndexTypesUtils {

    private Set<Class<?>> indexTypeClasses;

    @PostConstruct
    public void init() {
        List<IndexType<?>> indexTypes = IndexType.loadAll();
        indexTypeClasses = indexTypes.stream()
                .map(IndexType::dataClass)
                .collect(java.util.stream.Collectors.toSet());
    }

    public void checkDataObject(Object data) {
        if (indexTypeClasses.contains(data.getClass())) {
            return;
        }
        throw new IllegalStateException("Received search item with data of type '%s' which does not match any known IndexType data class".formatted(data.getClass().getName()));
    }

}
