package ch.admin.bit.jeap.opensearch.indextype;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class IndexTypeDefaultMethodsTest {

    @Test
    void versionCombinesMajorAndMinor() {
        IndexType<Object> type = indexType("JmeDecreeDocument", 2, 3);
        assertThat(type.version()).isEqualTo("2.3");
    }

    @Test
    void indexWriteAliasIncludesMajorVersion() {
        IndexType<Object> type = indexType("JmeDecreeDocument", 1, 0);
        assertThat(type.indexWriteAlias()).isEqualTo("jme_decree_document_v1_write");
    }

    @Test
    void indexWriteAliasConvertsOriginTypeToPascalCase() {
        assertThat(indexType("orderItem", 2, 0).indexWriteAlias()).isEqualTo("order_item_v2_write");
        assertThat(indexType("MySearchIndex", 1, 0).indexWriteAlias()).isEqualTo("my_search_index_v1_write");
        assertThat(indexType("already_snake", 1, 0).indexWriteAlias()).isEqualTo("already_snake_v1_write");
    }

    @Test
    void indexReadAliasIsOriginTypeWithReadSuffix() {
        IndexType<Object> type = indexType("JmeDecreeDocument", 1, 0);
        assertThat(type.indexReadAlias()).isEqualTo("jme_decree_document_read");
    }

    @Test
    void indexReadAliasIsIndependentOfVersion() {
        IndexType<Object> v1 = indexType("MyType", 1, 5);
        IndexType<Object> v2 = indexType("MyType", 2, 0);
        assertThat(v1.indexReadAlias()).isEqualTo(v2.indexReadAlias());
    }

    @Test
    void mappingVersionVersonStringCombinesMajorAndMinor() {
        MappingVersion<Object> mv = new MappingVersion<>(1, 2, Object.class, "mapping.json");
        assertThat(mv.versionString()).isEqualTo("1.2");
    }

    // ── minimal IndexType implementation for testing ──────────────────────────

    private static IndexType<Object> indexType(String originType, int major, int minor) {
        return new IndexType<>() {
            @Override public String system() { return "SYS"; }
            @Override public String originType() { return originType; }
            @Override public int majorVersion() { return major; }
            @Override public int minorVersion() { return minor; }
            @Override public String description() { return ""; }
            @Override public String documentationUrl() { return ""; }
            @Override public List<String> roles() { return List.of(); }
            @Override public Class<Object> dataClass() { return Object.class; }
            @Override public Supplier<InputStream> mappingDefinition() { return () -> null; }
        };
    }
}
