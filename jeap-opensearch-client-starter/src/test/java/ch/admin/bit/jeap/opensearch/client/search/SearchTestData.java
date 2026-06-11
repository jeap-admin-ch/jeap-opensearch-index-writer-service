package ch.admin.bit.jeap.opensearch.client.search;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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

    record TestDataV2(String name) {
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

    static final class TestIndexTypeV2 implements IndexType<TestDataV2> {
        private final List<String> roles;
        private final String systemOverride;

        TestIndexTypeV2(List<String> roles) {
            this(roles, null);
        }

        TestIndexTypeV2(List<String> roles, String systemOverride) {
            this.roles = List.copyOf(roles);
            this.systemOverride = systemOverride;
        }

        @Override
        public Class<TestDataV2> dataClass() {
            return TestDataV2.class;
        }

        @Override
        public String system() {
            return systemOverride != null ? systemOverride : "jme";
        }

        @Override
        public String originType() {
            return ORIGIN_TYPE;
        }

        @Override
        public int majorVersion() {
            return 2;
        }

        @Override
        public int minorVersion() {
            return 0;
        }

        @Override
        public String description() {
            return "test v2";
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
    }

    /**
     * Source JSON for a hit. {@code created}/{@code modified} are intentionally omitted —
     * Jackson's vanilla {@code JsonMapper} doesn't handle {@link java.time.Instant}
     * without JSR-310, and Jackson treats missing fields as {@code null}.
     */
    static JsonNode sourceJson(JsonMapper jsonMapper, String id, String bpId, String label) {
        ObjectNode root = jsonMapper.createObjectNode();
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

    /**
     * Source JSON for a multi-version hit — includes a {@code search_item.major_version}
     * field so that the multi-version dispatch logic can route to the correct
     * {@link IndexType}.
     */
    static JsonNode sourceJsonWithMajorVersion(JsonMapper jsonMapper,
            String id, String bpId, String dataFieldName, String dataFieldValue,
            int majorVersion) {
        ObjectNode root = jsonMapper.createObjectNode();
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
        dataNode.put(dataFieldName, dataFieldValue);
        ObjectNode searchItemNode = root.putObject("search_item");
        searchItemNode.put("major_version", majorVersion);
        return root;
    }

}
