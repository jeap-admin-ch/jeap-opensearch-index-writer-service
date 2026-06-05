package ch.admin.bit.jeap.opensearch.searchitem.model;


import ch.admin.bit.jeap.opensearch.indextype.SearchItem;

public record SearchItemContainer(
        int indexMajorVersion,
        int indexMinorVersion,
        SearchItem<?> searchItem) {
}
