package ch.admin.bit.jeap.opensearch.client.domain;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchItemTypedTest {

    private static final Origin VALID_ORIGIN = new Origin(
            "id-1",
            "1",
            "bp-1",
            "tenant-1",
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-01-02T00:00:00Z"),
            null);

    private static final String VALID_DATA = "payload";

    private static final IndexType<String> VALID_INDEX_TYPE = new TestStringIndexType();

    @Test
    void constructor_allValid_returnsRecordWithComponents() {
        SearchItemTyped<String> item = new SearchItemTyped<>(VALID_ORIGIN, VALID_DATA, VALID_INDEX_TYPE);

        assertThat(item.origin()).isSameAs(VALID_ORIGIN);
        assertThat(item.data()).isSameAs(VALID_DATA);
        assertThat(item.indexType()).isSameAs(VALID_INDEX_TYPE);
    }

    static Stream<Arguments> nullArguments() {
        return Stream.of(
                Arguments.of(null, VALID_DATA, VALID_INDEX_TYPE, "origin"),
                Arguments.of(VALID_ORIGIN, null, VALID_INDEX_TYPE, "data"),
                Arguments.of(VALID_ORIGIN, VALID_DATA, null, "indexType"));
    }

    @ParameterizedTest(name = "[{index}] null {3} -> NullPointerException mentioning \"{3}\"")
    @MethodSource("nullArguments")
    void constructor_nullComponent_throwsNullPointerExceptionMentioningField(
            Origin origin,
            String data,
            IndexType<String> indexType,
            String expectedFieldNameInMessage) {

        assertThatThrownBy(() -> new SearchItemTyped<>(origin, data, indexType))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(expectedFieldNameInMessage);
    }

    private static final class TestStringIndexType implements IndexType<String> {
        @Override
        public Class<String> dataClass() {
            return String.class;
        }

        @Override
        public String system() {
            return "jme";
        }

        @Override
        public String originType() {
            return "test-origin";
        }

        @Override
        public int majorVersion() {
            return 1;
        }

        @Override
        public int minorVersion() {
            return 0;
        }

        @Override
        public String description() {
            return "Test index type for SearchItemTypedTest";
        }

        @Override
        public String documentationUrl() {
            return "https://example.test/doc";
        }

        @Override
        public List<String> roles() {
            return List.of("test-role");
        }

        @Override
        public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }
    }
}
