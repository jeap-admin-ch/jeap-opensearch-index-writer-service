package ch.admin.bit.jeap.opensearch.searchitem.api;

import ch.admin.bit.jeap.opensearch.indextype.SearchItem;
import ch.admin.bit.jeap.opensearch.searchitem.api.exception.SearchItemsBadInputException;
import ch.admin.bit.jeap.opensearch.searchitem.model.SearchItemContainer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/index-api")
@Tag(name = "SearchItems", description = "Search and retrieve indexed items.")
@RequiredArgsConstructor
@Slf4j
public class SearchItemsController {

    private final SearchItemsProvider searchItemsProvider;

    private final IndexTypesUtils indexTypesUtils;

    @Operation(
            summary = "Search items by origin.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Success"),
                    @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                    @ApiResponse(responseCode = "403", description = "Access denied")
            },
            security = {@SecurityRequirement(name = "OIDC")}
    )
    @PreAuthorize("hasRole('searchitem', 'read')")
    @GetMapping(value = "/searchitems", produces = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("java:S1452")
    public ResponseEntity<SearchItem<?>> searchItem(
            @RequestParam("index_type") String indexType,
            @RequestParam("origin_id") String originId,
            @RequestParam(value = "origin_version", required = false) String originVersion) {

        log.info("Received request to search item with indexType '{}', originId '{}', originVersion '{}'", indexType, originId, originVersion);

        try {
            Optional<SearchItemContainer> itemContainerOptional  = searchItemsProvider.findSearchItem(indexType, originId, originVersion);

            if (itemContainerOptional.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            SearchItemContainer itemContainer = itemContainerOptional.get();
            indexTypesUtils.checkDataObject(itemContainer.searchItem().data());

            return ResponseEntity.ok()
                    .header("index-major-version", String.valueOf(itemContainer.indexMajorVersion()))
                    .header("index-minor-version", String.valueOf(itemContainer.indexMinorVersion()))
                    .body(itemContainer.searchItem());

        } catch (SearchItemsBadInputException _) {
            return ResponseEntity.badRequest().build();
        }
    }

}
