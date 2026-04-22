package ch.admin.bit.jeap.opensearch.indextype;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

public interface IndexType<T> extends IndexTypeDescriptor {

    /**
     * @return the concrete class of the data which belongs to this index type
     */
    Class<T> dataClass();

    /**
     * Loads all {@link IndexType} implementations registered via {@code ServiceLoader}
     * using the given class loader.
     */
    @SuppressWarnings({"rawtypes", "unchecked", "java:S1452"})
    static List<IndexType<?>> loadAll(ClassLoader classLoader) {
        List<IndexType<?>> result = new ArrayList<>();
        for (IndexType t : ServiceLoader.load(IndexType.class, classLoader)) {
            result.add(t);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Loads all {@link IndexType} implementations registered via {@code ServiceLoader}
     * using the current thread's context class loader.
     */
    @SuppressWarnings("java:S1452")
    static List<IndexType<?>> loadAll() {
        return loadAll(Thread.currentThread().getContextClassLoader());
    }
}
