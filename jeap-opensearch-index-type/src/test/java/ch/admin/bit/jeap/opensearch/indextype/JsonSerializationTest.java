package ch.admin.bit.jeap.opensearch.indextype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the @JsonProperty annotations produce the field names that
 * OpenSearch expects. A wrong field name here means documents are indexed
 * under the wrong key and queries break silently.
 */
class JsonSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void searchItemMetadataUsesSnakeCaseFieldNames() {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 2);
        JsonNode json = mapper.valueToTree(meta);

        assertThat(json.has("upserted_at")).isTrue();
        assertThat(json.has("minor_version")).isTrue();
        assertThat(json.path("minor_version").asInt()).isEqualTo(2);
        assertThat(json.has("upsertedAt")).isFalse();
        assertThat(json.has("minorVersion")).isFalse();
    }

    @Test
    void originUsesBpIdFieldName() {
        Origin origin = new Origin("id-1", "v1", "bp-42", null, Instant.EPOCH, Instant.EPOCH, null);
        JsonNode json = mapper.valueToTree(origin);

        assertThat(json.has("bp_id")).isTrue();
        assertThat(json.path("bp_id").asText()).isEqualTo("bp-42");
    }

    @Test
    void enrichedSearchItemUsesSearchItemKey() {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 1);
        Origin origin = new Origin("id-1", "v1", "bp-1", null, Instant.EPOCH, Instant.EPOCH, null);
        SearchItemIndexed<String> enriched = new SearchItemIndexed<>(meta, origin, "payload");

        JsonNode json = mapper.valueToTree(enriched);

        assertThat(json.has("search_item")).isTrue();
        assertThat(json.path("search_item").path("minor_version").asInt()).isEqualTo(1);
        assertThat(json.has("searchItem")).isFalse();
    }

    @Test
    void enrichedSearchItemRoundTrips() throws Exception {
        SearchItemMetadata meta = new SearchItemMetadata(Instant.EPOCH, 1);
        Origin origin = new Origin("id-rt", "v2", "bp-rt", null, Instant.EPOCH, Instant.EPOCH, null);
        SearchItemIndexed<String> original = new SearchItemIndexed<>(meta, origin, "data");

        String json = mapper.writeValueAsString(original);
        @SuppressWarnings("unchecked")
        SearchItemIndexed<String> deserialized = mapper.readValue(json,
                mapper.getTypeFactory().constructParametricType(SearchItemIndexed.class, String.class));

        assertThat(deserialized.data()).isEqualTo("data");
        assertThat(deserialized.origin().id()).isEqualTo("id-rt");
        assertThat(deserialized.origin().bpId()).isEqualTo("bp-rt");
        assertThat(deserialized.searchItem().minorVersion()).isEqualTo(1);
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
