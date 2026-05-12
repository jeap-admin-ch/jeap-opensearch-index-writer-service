package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

final class SearchTestData {

    static final String ORIGIN_TYPE = "TestOrigin";

    private SearchTestData() {
    }

    record TestData(String label) {
    }

    static final class TestIndexType implements IndexType<TestData> {
        private final String originType;
        private final List<String> roles;
        private final String indexReadAliasOverride;

        TestIndexType(List<String> roles) {
            this(ORIGIN_TYPE, roles, null);
        }

        TestIndexType(String originType, List<String> roles, String indexReadAliasOverride) {
            this.originType = originType;
            this.roles = List.copyOf(roles);
            this.indexReadAliasOverride = indexReadAliasOverride;
        }

        @Override
        public Class<TestData> dataClass() {
            return TestData.class;
        }

        @Override
        public String system() {
            return "jme";
        }

        @Override
        public String originType() {
            return originType;
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
            return "test";
        }

        @Override
        public String documentationUrl() {
            return "https://example.test/doc";
        }

        @Override
        public List<String> roles() {
            return roles;
        }

        @Override
        public Supplier<InputStream> mappingDefinition() {
            return () -> new ByteArrayInputStream("{}".getBytes());
        }

        @Override
        public String indexReadAlias() {
            return indexReadAliasOverride != null ? indexReadAliasOverride : IndexType.super.indexReadAlias();
        }
    }

    /**
     * Source JSON for a hit. {@code created}/{@code modified} are intentionally omitted —
     * Jackson's vanilla {@code ObjectMapper} doesn't handle {@link java.time.Instant}
     * without JSR-310, and Jackson treats missing fields as {@code null}.
     */
    static JsonNode sourceJson(ObjectMapper objectMapper, String id, String bpId, String label) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode originNode = root.putObject("origin");
        originNode.put("id", id);
        originNode.put("version", "1");
        if (bpId == null) {
            originNode.putNull("bp_id");
        } else {
            originNode.put("bp_id", bpId);
        }
        originNode.putNull("tenant");
        ObjectNode dataNode = root.putObject("data");
        dataNode.put("label", label);
        return root;
    }

}
