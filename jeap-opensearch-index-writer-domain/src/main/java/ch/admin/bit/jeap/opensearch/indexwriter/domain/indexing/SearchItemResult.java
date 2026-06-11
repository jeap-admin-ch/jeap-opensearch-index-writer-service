package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import tools.jackson.databind.JsonNode;

public record SearchItemResult(int indexMajorVersion, int indexMinorVersion, SearchItem<JsonNode> searchItem) {
}
