package ch.admin.bit.jeap.opensearch.client.auth;

import ch.admin.bit.jeap.opensearch.client.domain.SearchItemTyped;
import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indextype.Origin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

final class AuthTestData {

    private AuthTestData() {
    }

    static Origin origin(String bpId) {
        return new Origin(
                "id-1",
                "1",
                bpId,
                "tenant-1",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"),
                null);
    }

    static Origin originWithId(String id, String bpId) {
        return new Origin(
                id,
                "1",
                bpId,
                "tenant-1",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"),
                null);
    }

    static SearchItemTyped<String> searchItem(String bpId, IndexType<String> indexType) {
        return new SearchItemTyped<>(origin(bpId), "payload", indexType);
    }

    static SearchItemTyped<String> searchItemWithId(String id, String bpId, IndexType<String> indexType) {
        return new SearchItemTyped<>(originWithId(id, bpId), "payload", indexType);
    }

    static final class TestStringIndexType implements IndexType<String> {

        private final String originType;
        private final List<String> roles;

        TestStringIndexType(List<String> roles) {
            this("test-origin", roles);
        }

        TestStringIndexType(String originType, List<String> roles) {
            this.originType = originType;
            this.roles = List.copyOf(roles);
        }

        @Override
        public Class<String> dataClass() {
            return String.class;
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
            return "Test index type";
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
}
