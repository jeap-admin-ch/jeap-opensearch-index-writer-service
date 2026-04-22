package ch.admin.bit.jeap.opensearch.indexwriter.index.type.repository;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

public class TestIndexType implements IndexType<Object> {

    @Override
    public String system() {
        return "TEST";
    }

    @Override
    public String originType() {
        return "TestDocument";
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
        return "Test index type";
    }

    @Override
    public String documentationUrl() {
        return "";
    }

    @Override
    public List<String> roles() {
        return List.of("test_read");
    }

    @Override
    public Class<Object> dataClass() {
        return Object.class;
    }

    @Override
    public Supplier<InputStream> mappingDefinition() {
        return InputStream::nullInputStream;
    }
}
