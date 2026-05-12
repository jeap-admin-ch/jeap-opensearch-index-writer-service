package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class MultipleSearchItemsFoundExceptionTest {

    @Test
    void isASearchItemClientException() {
        assertThat(SearchItemClientException.class)
                .isAssignableFrom(MultipleSearchItemsFoundException.class);
    }

    @Test
    void message_containsAllFourParameters() {
        IndexType<String> indexType = new TestIndexType("InspectionDocument");
        List<String> indices = List.of("inspection_v1", "inspection_v2");

        MultipleSearchItemsFoundException ex =
                new MultipleSearchItemsFoundException(indexType, indices, "doc-42", 3);

        assertThat(ex.getMessage())
                .contains("doc-42")
                .contains(indexType.getClass().getSimpleName())
                .contains("inspection_v1")
                .contains("inspection_v2")
                .contains("3");
        assertThat(ex.getCause()).isNull();
    }

    private record TestIndexType(String originType) implements IndexType<String> {
        @Override public Class<String> dataClass() { return String.class; }
        @Override public String system() { return "jme"; }
        @Override public int majorVersion() { return 1; }
        @Override public int minorVersion() { return 0; }
        @Override public String description() { return "test"; }
        @Override public String documentationUrl() { return "https://example.test/doc"; }
        @Override public List<String> roles() { return List.of(); }
        @Override public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }
    }
}
