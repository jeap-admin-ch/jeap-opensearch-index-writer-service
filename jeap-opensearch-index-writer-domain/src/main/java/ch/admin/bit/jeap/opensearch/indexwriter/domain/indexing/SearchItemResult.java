package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import tools.jackson.databind.JsonNode;

// indexType is only reported by providers that answered a schema-major request, otherwise null
public record SearchItemResult(int indexMajorVersion, int indexMinorVersion, SearchItem<JsonNode> searchItem, String indexType) {

    public SearchItemResult(int indexMajorVersion, int indexMinorVersion, SearchItem<JsonNode> searchItem) {
        this(indexMajorVersion, indexMinorVersion, searchItem, null);
    }
}
