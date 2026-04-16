package ch.admin.bit.jeap.opensearch.indextype;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Supplier;

public interface IndexType<T> {

    /**
     * @return the system to which the index type belongs to
     */
    String system();

    /**
     * @return the type of the business object to be indexed
     */
    String originType();

    /**
     * @return the major version of this index type
     */
    int majorVersion();

    /**
     * @return the minor version of this index type
     */
    int minorVersion();

    /**
     * @return description of this index type
     */
    String description();

    /**
     * @return url where to find the documentation of this index type
     */
    String documentationUrl();

    /**
     * @return the roles that are authorized to read this index type
     */
    List<String> roles();

    /**
     * @return the concrete class of the data which belongs to this index type
     */
    Class<T> dataClass();

    /**
     * @return the mapping definition for this index type as an input stream. The mapping definition must be a valid OpenSearch mapping definition in JSON format.
     */
    Supplier<InputStream> mappingDefinition();

    default String version() {
        return majorVersion() + "." + minorVersion();
    }

    default String indexWriteAlias() {
        return originType() + "_V" + majorVersion() + "_write";
    }

    default String indexReadAlias() {
        return originType() + "_read";
    }

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
