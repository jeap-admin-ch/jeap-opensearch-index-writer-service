package ch.admin.bit.jeap.opensearch.indexwriter.web.it;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

public class ITTestIndexTypeFeatureFlag implements IndexType<Object> {

    @Override
    public String system() {
        return "IT";
    }

    @Override
    public String originType() {
        return "TestDocumentFeatureFlag";
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
        return "IT test index type for feature flag";
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
    public Class<Object> dataClass() {
        return Object.class;
    }

    @Override
    public Supplier<InputStream> mappingDefinition() {
        return InputStream::nullInputStream;
    }
}
