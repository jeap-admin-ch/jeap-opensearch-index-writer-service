package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

public class ITTestNestedIndexType implements IndexType<ITTestNestedIndexTypeData> {

    @Override
    public String system() {
        return "IT";
    }

    @Override
    public String originType() {
        return "TestDocumentNested";
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
        return "IT test index type with object and nested data fields";
    }

    @Override
    public String documentationUrl() {
        return "";
    }

    @Override
    public List<String> roles() {
        return List.of();
    }

    @Override
    public Class<ITTestNestedIndexTypeData> dataClass() {
        return ITTestNestedIndexTypeData.class;
    }

    @Override
    public Supplier<InputStream> mappingDefinition() {
        return () -> getClass().getResourceAsStream("/it-test-nested-index-mapping.json");
    }
}
