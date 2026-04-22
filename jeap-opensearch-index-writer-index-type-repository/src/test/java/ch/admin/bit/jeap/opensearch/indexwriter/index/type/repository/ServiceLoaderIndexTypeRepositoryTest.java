package ch.admin.bit.jeap.opensearch.indexwriter.index.type.repository;

import ch.admin.bit.jeap.opensearch.indextype.IndexTypeDescriptor;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.ConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceLoaderIndexTypeRepositoryTest {

    private ServiceLoaderIndexTypeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ServiceLoaderIndexTypeRepository();
        repository.load(Thread.currentThread().getContextClassLoader());
    }

    @Test
    void getAllReturnsRegisteredIndexTypes() {
        List<IndexTypeDescriptor> all = repository.getAll();

        assertThat(all).hasSize(1);
        assertThat(all.getFirst()).isInstanceOf(TestIndexType.class);
    }

    @Test
    void findByOriginTypeAndMajorVersionReturnsMatchingType() {
        Optional<IndexTypeDescriptor> result = repository.findByOriginTypeAndMajorVersion("TestDocument", 1);

        assertThat(result).isPresent();
        assertThat(result.get().system()).isEqualTo("TEST");
        assertThat(result.get().majorVersion()).isEqualTo(1);
        assertThat(result.get().minorVersion()).isZero();
        assertThat(result.get().roles()).containsExactly("test_read");
    }

    @Test
    void findByOriginTypeAndMajorVersionReturnsEmptyForUnknownType() {
        assertThat(repository.findByOriginTypeAndMajorVersion("Unknown", 1)).isEmpty();
    }

    @Test
    void findByOriginTypeAndMajorVersionReturnsEmptyForWrongMajorVersion() {
        assertThat(repository.findByOriginTypeAndMajorVersion("TestDocument", 2)).isEmpty();
    }

    @Test
    void findByOriginTypeAndMajorVersionReturnsHighestMinorVersionWhenMultipleFound() throws Exception {
        ServiceLoaderIndexTypeRepository repo = new ServiceLoaderIndexTypeRepository();
        Field field = ServiceLoaderIndexTypeRepository.class.getDeclaredField("indexTypes");
        field.setAccessible(true);
        field.set(repo, List.of(new TestIndexType(), new TestIndexTypeV1Minor1()));

        Optional<IndexTypeDescriptor> result = repo.findByOriginTypeAndMajorVersion("TestDocument", 1);

        assertThat(result).isPresent();
        assertThat(result.get().minorVersion()).isEqualTo(1);
    }

    @Test
    void failsToStartWhenNoIndexTypesOnClasspath() {
        ServiceLoaderIndexTypeRepository emptyRepo = new ServiceLoaderIndexTypeRepository();

        // Use an empty classloader with no parent to find no ServiceLoader registrations
        URLClassLoader emptyClassLoader = new URLClassLoader(new java.net.URL[0], null);

        assertThatThrownBy(() -> emptyRepo.load(emptyClassLoader))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("No IndexType implementations found");
    }

    private static class TestIndexTypeV1Minor1 extends TestIndexType {
        @Override
        public int minorVersion() {
            return 1;
        }
    }
}
