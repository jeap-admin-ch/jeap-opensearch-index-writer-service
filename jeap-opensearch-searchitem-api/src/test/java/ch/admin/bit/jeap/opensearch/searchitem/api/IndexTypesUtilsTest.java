package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.searchitem.api.model.TestDataV1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class IndexTypesUtilsTest {

    private final IndexTypesUtils indexTypesUtils = new IndexTypesUtils();

    @BeforeEach
    void setup(){
        indexTypesUtils.init();
    }

    @Test
    void testCheckDataObject() {
        SearchItem<TestDataV1> searchItem = new SearchItem<>(mock(Origin.class), new TestDataV1("test"));
        assertDoesNotThrow(() -> indexTypesUtils.checkDataObject(searchItem.data()));
    }

    @Test
    void testCheckDataObject_indexType_notFound_throwsException() {
        Class<?> aClass = "not_found".getClass();
        assertThrows(IllegalStateException.class, () -> indexTypesUtils.checkDataObject(aClass));
    }

}
