package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import com.fasterxml.jackson.databind.JsonNode;

public record SearchItemResult(int indexMajorVersion, int indexMinorVersion, SearchItem<JsonNode> searchItem) {
}
