package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indextype.SearchItemIndexed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Slf4j
class DataFieldValidator {

    private static final String PROPERTIES_JSON_KEY = "properties";
    private static final String DATA_JSON_KEY = "data";

    private final ObjectMapper objectMapper;
    private final Map<String, Set<String>> declaredDataFieldsByAlias = new ConcurrentHashMap<>();

    void cacheMapping(String indexWriteAlias, JsonObject mappings) {
        JsonObject properties = mappings.getJsonObject(PROPERTIES_JSON_KEY);
        if (properties == null || !properties.containsKey(DATA_JSON_KEY)) {
            return;
        }
        JsonObject dataObject = properties.getJsonObject(DATA_JSON_KEY);
        if (dataObject == null || !dataObject.containsKey(PROPERTIES_JSON_KEY)) {
            return;
        }
        Set<String> declared = new HashSet<>(dataObject.getJsonObject(PROPERTIES_JSON_KEY).keySet());
        declaredDataFieldsByAlias.put(indexWriteAlias, Collections.unmodifiableSet(declared));
        log.debug("Cached {} declared data field(s) for index '{}': {}", declared.size(), indexWriteAlias, declared);
    }

    void validateDataFields(String indexWriteAlias, String documentId, SearchItemIndexed<?> searchItem) {
        Set<String> declaredFields = declaredDataFieldsByAlias.get(indexWriteAlias);
        if (declaredFields == null || searchItem.data() == null) {
            return;
        }
        JsonNode dataNode = objectMapper.valueToTree(searchItem.data());
        if (!dataNode.isObject()) {
            return;
        }
        Set<String> undeclared = new HashSet<>();
        dataNode.fieldNames().forEachRemaining(field -> {
            if (!declaredFields.contains(field)) {
                undeclared.add(field);
            }
        });
        if (!undeclared.isEmpty()) {
            log.warn("Document '{}' for index '{}' contains data fields not declared in the mapping: {} — they will be silently dropped by OpenSearch",
                    documentId, indexWriteAlias, undeclared);
        }
    }
}
