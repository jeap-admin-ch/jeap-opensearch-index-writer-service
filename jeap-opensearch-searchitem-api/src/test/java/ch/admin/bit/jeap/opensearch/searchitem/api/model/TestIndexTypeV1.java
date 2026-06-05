package ch.admin.bit.jeap.opensearch.searchitem.api.model;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

public final class TestIndexTypeV1 implements IndexType<TestDataV1> {

    public String system() {
        return "system";
    }

    public String originType() {
        return "TestIndexTypeV1";
    }

    public int majorVersion() {
        return 1;
    }

    public int minorVersion() {
        return 0;
    }

    public String description() {
        return null;
    }

    public String documentationUrl() {
        return null;
    }

    public List<String> roles() {
        return List.of();
    }

    public Class<TestDataV1> dataClass() {
        return TestDataV1.class;
    }

    public Supplier<InputStream> mappingDefinition() {
        return null;
    }
}
