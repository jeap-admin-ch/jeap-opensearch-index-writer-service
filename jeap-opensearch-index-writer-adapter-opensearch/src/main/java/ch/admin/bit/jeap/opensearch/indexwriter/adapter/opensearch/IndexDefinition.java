package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.json.JsonObject;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;

record IndexDefinition(TypeMapping mapping, JsonObject mappings, JsonObject analysis) {
}
