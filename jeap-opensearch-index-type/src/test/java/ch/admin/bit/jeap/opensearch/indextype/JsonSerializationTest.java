package ch.admin.bit.jeap.opensearch.indextype;

import tools.jackson.databind.cfg.DateTimeFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the @JsonProperty annotations produce the field names that
 * OpenSearch expects. A wrong field name here means documents are indexed
 * under the wrong key and queries break silently.
 */
class JsonSerializationTest {

    private JsonMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    void searchItemMetadataUsesSnakeCaseFieldNames() {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 1, 2);
        JsonNode json = mapper.valueToTree(meta);

        assertThat(json.has("upserted_at")).isTrue();
        assertThat(json.has("major_version")).isTrue();
        assertThat(json.path("major_version").asInt()).isEqualTo(1);
        assertThat(json.has("minor_version")).isTrue();
        assertThat(json.path("minor_version").asInt()).isEqualTo(2);
        assertThat(json.has("upsertedAt")).isFalse();
        assertThat(json.has("majorVersion")).isFalse();
        assertThat(json.has("minorVersion")).isFalse();
    }

    @Test
    void originUsesBpIdFieldName() {
        Origin origin = new Origin("id-1", "v1", "bp-42", null, Instant.EPOCH, Instant.EPOCH, null);
        JsonNode json = mapper.valueToTree(origin);

        assertThat(json.has("bp_id")).isTrue();
        assertThat(json.path("bp_id").asString()).isEqualTo("bp-42");
    }

    @Test
    void enrichedSearchItemUsesSearchItemKey() {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 1, 2);
        Origin origin = new Origin("id-1", "v1", "bp-1", null, Instant.EPOCH, Instant.EPOCH, null);
        SearchItemIndexed<String> enriched = new SearchItemIndexed<>(meta, origin, "payload");

        JsonNode json = mapper.valueToTree(enriched);

        assertThat(json.has("search_item")).isTrue();
        assertThat(json.path("search_item").path("major_version").asInt()).isEqualTo(1);
        assertThat(json.path("search_item").path("minor_version").asInt()).isEqualTo(2);
        assertThat(json.has("searchItem")).isFalse();
    }

    @Test
    void enrichedSearchItemRoundTrips() {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 1, 0);
        Origin origin = new Origin("id-rt", "v2", "bp-rt", null, Instant.EPOCH, Instant.EPOCH, null);
        SearchItemIndexed<String> original = new SearchItemIndexed<>(meta, origin, "data");

        String json = mapper.writeValueAsString(original);
        @SuppressWarnings("unchecked")
        SearchItemIndexed<String> deserialized = mapper.readValue(json,
                mapper.getTypeFactory().constructParametricType(SearchItemIndexed.class, String.class));

        assertThat(deserialized.data()).isEqualTo("data");
        assertThat(deserialized.origin().id()).isEqualTo("id-rt");
        assertThat(deserialized.origin().bpId()).isEqualTo("bp-rt");
        assertThat(deserialized.searchItem().majorVersion()).isEqualTo(1);
        assertThat(deserialized.searchItem().minorVersion()).isZero();
    }

    @Test
    void originRoundTrips() throws Exception {
        Origin original = new Origin("o-id", "v3", "bp-99", null, Instant.EPOCH, Instant.EPOCH, null);

        String json = mapper.writeValueAsString(original);
        Origin deserialized = mapper.readValue(json, Origin.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.version()).isEqualTo(original.version());
        assertThat(deserialized.bpId()).isEqualTo(original.bpId());
        assertThat(deserialized.created()).isEqualTo(original.created());
        assertThat(deserialized.modified()).isEqualTo(original.modified());
    }
}
