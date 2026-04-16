package ch.admin.bit.jeap.opensearch.registry.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchTypeMapperTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "keyword,         String",
            "text,            String",
            "wildcard,        String",
            "constant_keyword, String",
            "integer,         Integer",
            "short,           Integer",
            "byte,            Integer",
            "long,            Long",
            "float,           Float",
            "half_float,      Float",
            "double,          Double",
            "scaled_float,    Double",
            "boolean,         Boolean",
            "date,            java.time.Instant",
            "binary,          String",
            "unknown_type,    Object",
    })
    void mapsOpenSearchTypeToJavaType(String osType, String expectedJavaType) {
        assertThat(OpenSearchTypeMapper.toJavaType(osType)).isEqualTo(expectedJavaType);
    }

    @Test
    void typeMatchingIsCaseInsensitive() {
        assertThat(OpenSearchTypeMapper.toJavaType("KEYWORD")).isEqualTo("String");
        assertThat(OpenSearchTypeMapper.toJavaType("Date")).isEqualTo("java.time.Instant");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "document_id,      documentId",
            "created_at,       createdAt",
            "decree_reference, decreeReference",
            "simple,           simple",
            "already_camel,    alreadyCamel",
            "a_b_c,            aBC",
            "no_change,        noChange",
    })
    void convertsToCamelCase(String input, String expected) {
        assertThat(OpenSearchTypeMapper.toCamelCase(input)).isEqualTo(expected);
    }

    @Test
    void camelCaseWithNullOrEmptyInput() {
        assertThat(OpenSearchTypeMapper.toCamelCase(null)).isNull();
        assertThat(OpenSearchTypeMapper.toCamelCase("")).isEmpty();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "document_id,      DocumentId",
            "foo,              Foo",
            "decree_reference, DecreeReference",
    })
    void convertsToClassName(String input, String expected) {
        assertThat(OpenSearchTypeMapper.toClassName(input)).isEqualTo(expected);
    }
}
