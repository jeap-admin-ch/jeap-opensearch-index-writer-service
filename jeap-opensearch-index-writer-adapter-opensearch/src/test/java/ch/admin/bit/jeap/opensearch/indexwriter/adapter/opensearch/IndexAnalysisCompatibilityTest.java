package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexAnalysisCompatibilityTest {
    @Mock
    private OpenSearchClient client;
    @Mock
    private OpenSearchGenericClient generic;

    @Test
    void normalizesScalarSettingsAndEmptyComponentMaps() throws IOException {
        deployed("""
                {"orders_v1-000001":{"settings":{"index":{"analysis":{
                  "filter":{"limit":{"type":"length","min":"2"}},
                  "analyzer":{}
                }}}}}
                """, "{\"orders_v1-000001\":{\"mappings\":{}}}");
        IndexDefinition definition = definition("""
                {"filter":{"limit":{"min":2,"type":"length"}}}
                """, "{}");
        assertThatNoException().isThrownBy(() -> check(definition, 0));
    }

    @Test
    void preservesEmptyNativeComponentOptionsDuringComparison() throws IOException {
        deployed("""
                {"orders_v1-000001":{"settings":{"index":{"analysis":{"filter":{"native":{"type":"plugin","options":{}}}}}}}}
                """, "{}");
        assertThatThrownBy(() -> check(definition("{\"filter\":{\"native\":{\"type\":\"plugin\"}}}", "{}"), 0))
                .hasMessageContaining("settings.analysis");
    }

    @Test
    void checksOlderPartitionsEvenWhenCurrentPartitionMatches() throws IOException {
        deployed("""
                {"orders_v1-000002":{"settings":{"index":{}}},
                 "orders_v1-000001":{"settings":{"index":{"analysis":{"analyzer":{"default":{"type":"keyword"}}}}}}}
                """, "{\"orders_v1-000002\":{\"mappings\":{}}}");
        assertThatThrownBy(() -> check(definition("{}", "{}"), 0))
                .hasMessageContaining("orders_v1-000001").hasMessageContaining("settings.analysis");
    }

    @Test
    void rejectsDowngradeBeforeUpdate() throws IOException {
        deployed("{\"orders_v1-000001\":{\"settings\":{\"index\":{}}}}",
                "{\"orders_v1-000001\":{\"mappings\":{\"_meta\":{\"schema_version\":\"2\"}}}}");
        assertThatThrownBy(() -> check(definition("{}", "{}"), 1)).hasMessageContaining("downgrade");
    }

    @Test
    void missingMappingResponseFailsClosed() throws IOException {
        deployed("{\"orders_v1-000001\":{\"settings\":{\"index\":{}}}}", "{}");
        assertThatThrownBy(() -> check(definition("{}", "{}"), 0))
                .hasMessageContaining("Failed to check deployed analysis").hasCauseInstanceOf(IOException.class);
    }

    @Test
    void deniedSettingsPermissionFailsClosed() throws IOException {
        when(client.generic()).thenReturn(generic);
        Response denied = mock(Response.class);
        when(denied.getStatus()).thenReturn(403);
        when(generic.execute(any(Request.class))).thenReturn(denied);
        assertThatThrownBy(() -> check(definition("{}", "{}"), 0))
                .hasMessageContaining("orders_v1_write").hasRootCauseMessage("OpenSearch _settings returned HTTP 403");
    }

    private void check(IndexDefinition definition, int version) {
        new IndexAnalysisCompatibility(client).ensureCompatible("orders_v1_write", definition, version);
    }

    private void deployed(String settings, String mappings) throws IOException {
        when(client.generic()).thenReturn(generic);
        Response settingsResponse = response(settings);
        Response mappingsResponse = response(mappings);
        when(generic.execute(any(Request.class))).thenReturn(settingsResponse, mappingsResponse);
    }

    private Response response(String json) {
        Response response = mock(Response.class);
        org.mockito.Mockito.lenient().when(response.getStatus()).thenReturn(200);
        org.mockito.Mockito.lenient().when(response.getBody()).thenReturn(Optional.of(Body.from(json.getBytes(StandardCharsets.UTF_8), "application/json")));
        return response;
    }

    private IndexDefinition definition(String analysis, String mappings) {
        return new IndexDefinition(null, json(mappings), json(analysis));
    }

    private JsonObject json(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}
