package ch.admin.bit.jeap.opensearch.indextype;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchItemIndexedTest {

    @Test
    void ofSearchItem() {
        Object data = new Object();
        Origin origin = mock(Origin.class);
        SearchItem<Object> searchItem = mock(SearchItem.class);
        when(searchItem.data()).thenReturn(data);
        when(searchItem.origin()).thenReturn(origin);
        SearchItemMetadata metaData = mock(SearchItemMetadata.class);

        SearchItemIndexed<?> enrichedSearchItem = SearchItemIndexed.of(searchItem, metaData);

        assertEquals(data, enrichedSearchItem.data());
        assertEquals(origin, enrichedSearchItem.origin());
        assertEquals(metaData, enrichedSearchItem.searchItem());
    }

    @Test
    void toBase() {
        Object data = new Object();
        Origin origin = mock(Origin.class);
        SearchItem<Object> searchItem = mock(SearchItem.class);
        when(searchItem.data()).thenReturn(data);
        when(searchItem.origin()).thenReturn(origin);
        SearchItemMetadata metaData = mock(SearchItemMetadata.class);
        SearchItemIndexed<?> enrichedSearchItem = SearchItemIndexed.of(searchItem, metaData);

        SearchItem<?> base = enrichedSearchItem.toBase();
        assertEquals(data, base.data());
        assertEquals(origin, base.origin());
    }
}