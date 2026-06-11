package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.opensearch.indextype.Origin;
import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.searchitem.api.exception.SearchItemsBadInputException;
import ch.admin.bit.jeap.opensearch.searchitem.model.SearchItemContainer;
import ch.admin.bit.jeap.security.resource.semanticAuthentication.SemanticApplicationRole;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import ch.admin.bit.jeap.security.test.resource.JeapAuthenticationTestTokenBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SearchItemsController.class)
@ContextConfiguration(classes = OpenSearchSearchItemApiTestConfig.class)
@AutoConfigureMockMvc
class SearchItemsControllerTest {

    private static final String INDEX_TYPE = "test-index-type";
    private static final String OBJECT_BUCKET = "my-bucket";
    private static final String OBJECT_KEY = "my-key";
    private static final String ORIGIN_VERSION = "1.0.0";
    private static final String ORIGIN_ID = OBJECT_BUCKET + ":" + OBJECT_KEY;
    private static final String EXPECTED_SEARCH_ITEM_BODY = "{\"origin\":{\"id\":\"my-bucket:my-key\",\"version\":\"1.0.0\",\"bp_id\":null,\"tenant\":null,\"created\":null,\"modified\":null,\"reference\":{\"url\":\"https://hello.ch\"}},\"data\":\"Hello World\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private SearchItemsProvider searchItemsProvider;
    @MockitoBean
    private IndexTypesUtils indexTypesUtils;

    private static final SemanticApplicationRole READ_ROLE = SemanticApplicationRole.builder()
            .system("jme")
            .resource("searchitem")
            .operation("read")
            .build();

    private static final SemanticApplicationRole FOO_ROLE = SemanticApplicationRole.builder()
            .system("jme")
            .resource("searchitem")
            .operation("foo")
            .build();

    @Test
    void searchItem_whenArtifactFound_returnsOkWithSearchItemAndVersionHeaders() throws Exception {
        SearchItem<String> searchItem = new SearchItem<>(
                new Origin(ORIGIN_ID, ORIGIN_VERSION, null, null, null, null, Map.of("url", "https://hello.ch")),
                "Hello World");
        SearchItemContainer container = new SearchItemContainer(2, 3, searchItem);


        when(searchItemsProvider.findSearchItem(INDEX_TYPE, ORIGIN_ID, null))
                .thenReturn(Optional.of(container));

        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .with(authentication(createAuthenticationForUserRoles(READ_ROLE)))
                )
                .andExpect(status().isOk())
                .andExpect(content().string(EXPECTED_SEARCH_ITEM_BODY))
                .andExpect(header().string("index-major-version", "2"))
                .andExpect(header().string("index-minor-version", "3"));
    }

    @Test
    void searchItem_withVersion_whenArtifactFound_returnsOkWithSearchItemAndVersionHeaders() throws Exception {
        SearchItem<String> searchItem = new SearchItem<>(
                new Origin(ORIGIN_ID, ORIGIN_VERSION, null, null, null, null, Map.of("url", "https://hello.ch")),
                "Hello World");
        SearchItemContainer container = new SearchItemContainer(2, 3, searchItem);

        when(searchItemsProvider.findSearchItem(INDEX_TYPE, ORIGIN_ID, ORIGIN_VERSION))
                .thenReturn(Optional.of(container));

        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .param("origin_version", ORIGIN_VERSION)
                        .with(authentication(createAuthenticationForUserRoles(READ_ROLE)))
                )
                .andExpect(status().isOk())
                .andExpect(content().string(EXPECTED_SEARCH_ITEM_BODY))
                .andExpect(header().string("index-major-version", "2"))
                .andExpect(header().string("index-minor-version", "3"));
    }

    @Test
    void searchItem_whenArtifactNotFound_returnsNotFound() throws Exception {
        when(searchItemsProvider.findSearchItem(any(), any(), eq(null)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .with(authentication(createAuthenticationForUserRoles(READ_ROLE)))
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void searchItem_whenBadInput_returnsBadRequest() throws Exception {
        when(searchItemsProvider.findSearchItem(any(), any(), eq(null)))
                .thenThrow(new SearchItemsBadInputException("Bad input"));

        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .with(authentication(createAuthenticationForUserRoles(READ_ROLE)))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchItem_whenNoReadRole_thenReturnsForbidden() throws Exception {
        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .with(authentication(createAuthenticationForUserRoles(FOO_ROLE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verify(searchItemsProvider, never()).findSearchItem(any(), any(), any());
    }

    @Test
    void searchItem_whenNoAuthentication_thenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/index-api/searchitems")
                        .param("index_type", INDEX_TYPE)
                        .param("origin_id", ORIGIN_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(searchItemsProvider, never()).findSearchItem(any(), any(), any());
    }

    private JeapAuthenticationToken createAuthenticationForUserRoles(SemanticApplicationRole... userroles) {
        return JeapAuthenticationTestTokenBuilder.create().withUserRoles(userroles).build();
    }
}
