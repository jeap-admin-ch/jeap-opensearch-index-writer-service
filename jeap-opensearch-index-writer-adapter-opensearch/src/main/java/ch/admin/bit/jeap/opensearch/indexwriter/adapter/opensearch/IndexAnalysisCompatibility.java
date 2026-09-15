package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
class IndexAnalysisCompatibility {
    private static final Set<String> COMPONENTS = Set.of("analyzer", "normalizer", "tokenizer", "filter", "char_filter");
    private static final List<String> PARAMETERS = List.of("analyzer", "normalizer", "search_analyzer", "search_quote_analyzer");

    private final OpenSearchClient client;

    static JsonObject readAnalysis(JsonObject root) {
        if (!Set.of("mappings", "settings").containsAll(root.keySet()) || !(root.get("mappings") instanceof JsonObject)) {
            throw new IllegalArgumentException("Index definition must contain mappings and only optional settings.analysis");
        }
        JsonObject settings = object(root, "settings");
        if (!Set.of("analysis").containsAll(settings.keySet())) {
            throw new IllegalArgumentException("Only settings.analysis belongs to the IndexType; operational settings belong to the writer");
        }
        JsonObject analysis = object(settings, "analysis");
        if (!COMPONENTS.containsAll(analysis.keySet())) {
            throw new IllegalArgumentException("Unsupported analysis section");
        }
        analysis.forEach((category, value) -> {
            JsonObject components = object(analysis, category);
            components.keySet().forEach(name -> object(components, name));
        });
        return analysis;
    }

    void ensureCompatible(String alias, IndexDefinition desired, int minorVersion) {
        String pattern = IndexNaming.indexPattern(alias) + "," + alias;
        try {
            JsonObject settings = get(pattern, "_settings");
            if (settings.isEmpty()) {
                return;
            }
            JsonObject mappings = get(pattern, "_mapping");
            for (String index : settings.keySet()) {
                JsonObject installed = object(object(object(settings, index), "settings"), "index");
                if (!normalizedAnalysis(object(installed, "analysis")).equals(normalizedAnalysis(desired.analysis()))) {
                    throw OpenSearchIndexWriterException.incompatibleAnalysis(index, "settings.analysis differs from the physical index");
                }
                if (!mappings.containsKey(index)) {
                    throw new IOException("Missing mapping response for " + index);
                }
                JsonObject existingMapping = object(object(mappings, index), "mappings");
                JsonValue version = object(existingMapping, "_meta").get("schema_version");
                if (version != null && Integer.parseInt(scalar(version)) > minorVersion) {
                    throw OpenSearchIndexWriterException.incompatibleAnalysis(index, "mapping version downgrade is not supported");
                }
                String defaultAnalyzer = object(desired.analysis(), "analyzer").containsKey("default") ? "default" : "standard";
                compareFields(index, existingMapping, desired.mappings(), "mappings", defaultAnalyzer);
            }
        } catch (IOException | IllegalArgumentException | JsonException e) {
            throw OpenSearchIndexWriterException.analysisCheckFailed(alias, e);
        }
    }

    private JsonObject get(String pattern, String operation) throws IOException {
        var request = Requests.create("GET", "/" + pattern + "/" + operation, List.of(),
                Map.of("allow_no_indices", "true", "ignore_unavailable", "true", "expand_wildcards", "all"), null);
        try (var response = client.generic().execute(request)) {
            if (response.getStatus() != 200) {
                throw new IOException("OpenSearch " + operation + " returned HTTP " + response.getStatus());
            }
            var body = response.getBody().orElseThrow(() -> new IOException("Empty OpenSearch " + operation + " response"));
            try (var reader = Json.createReader(body.body())) {
                return reader.readObject();
            }
        }
    }

    private static void compareFields(String index, JsonObject previous, JsonObject current, String path, String defaultAnalyzer) {
        for (String parameter : PARAMETERS) {
            if (!Objects.equals(analysisParameter(previous, parameter, defaultAnalyzer), analysisParameter(current, parameter, defaultAnalyzer))) {
                throw OpenSearchIndexWriterException.incompatibleAnalysis(index, path + "." + parameter + " changed");
            }
        }
        for (String section : List.of("properties", "fields")) {
            object(previous, section).forEach((name, value) -> {
                JsonObject nextFields = object(current, section);
                if (nextFields.containsKey(name)) {
                    compareFields(index, (JsonObject) value, object(nextFields, name), path + "." + name, defaultAnalyzer);
                } else {
                    throw OpenSearchIndexWriterException.incompatibleAnalysis(index, path + "." + name + " removed");
                }
            });
        }
    }

    private static JsonValue analysisParameter(JsonObject field, String parameter, String defaultAnalyzer) {
        // OpenSearch omits search analyzers that equal their fallback when returning mappings.
        JsonValue value = switch (parameter) {
            case "analyzer" -> field.getOrDefault(parameter, Json.createValue(defaultAnalyzer));
            case "search_analyzer" -> field.getOrDefault(parameter, analysisParameter(field, "analyzer", defaultAnalyzer));
            case "search_quote_analyzer" -> field.getOrDefault(parameter, analysisParameter(field, "search_analyzer", defaultAnalyzer));
            default -> field.get(parameter);
        };
        if (!"normalizer".equals(parameter) && value instanceof JsonString string && "default".equals(string.getString())) {
            return Json.createValue(defaultAnalyzer);
        }
        return value;
    }

    private static JsonObject normalizedAnalysis(JsonObject analysis) {
        var builder = Json.createObjectBuilder();
        analysis.forEach((key, value) -> {
            if (!(value instanceof JsonObject components) || !components.isEmpty()) {
                builder.add(key, normalized(value));
            }
        });
        return builder.build();
    }

    private static JsonValue normalized(JsonValue value) {
        if (value instanceof JsonObject obj) {
            var builder = Json.createObjectBuilder();
            obj.forEach((key, child) -> builder.add(key, normalized(child)));
            return builder.build();
        }
        if (value instanceof JsonArray array) {
            var builder = Json.createArrayBuilder();
            array.forEach(child -> builder.add(normalized(child)));
            return builder.build();
        }
        // OpenSearch returns scalar settings as strings, including numbers and booleans.
        return Json.createValue(scalar(value));
    }

    private static String scalar(JsonValue value) {
        return value instanceof JsonString string ? string.getString() : value.toString();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonValue value = parent.get(key);
        if (value == null) {
            return JsonValue.EMPTY_JSON_OBJECT;
        }
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException(key + " must be an object");
    }
}
