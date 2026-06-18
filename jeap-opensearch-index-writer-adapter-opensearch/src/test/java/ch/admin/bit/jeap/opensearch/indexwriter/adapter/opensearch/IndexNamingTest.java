package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexNamingTest {

    @Test
    void logicalName_stripsWriteSuffix() {
        assertThat(IndexNaming.logicalName("orders_V1_write")).isEqualTo("orders_v1");
    }

    @Test
    void indexPattern_stripsWriteSuffix_andAppendsRolloverWildcard() {
        assertThat(IndexNaming.indexPattern("orders_V1_write")).isEqualTo("orders_v1-*");
    }

    @Test
    void indexPattern_withoutWriteSuffix_appendsRolloverWildcard() {
        assertThat(IndexNaming.indexPattern("orders_V1")).isEqualTo("orders_v1-*");
    }

    @Test
    void initialPhysicalIndexName_stripsWriteSuffix_andAppendsSuffix() {
        assertThat(IndexNaming.initialPhysicalIndexName("orders_v1_write")).isEqualTo("orders_v1-000001");
    }
}
