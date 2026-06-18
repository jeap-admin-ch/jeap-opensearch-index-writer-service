package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.GetAliasRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
class PhysicalIndexManager {

    private final OpenSearchClient openSearchClient;

    void ensureWriteIndexExists(String indexWriteAlias) {
        GetAliasRequest getAliasRequest = new GetAliasRequest.Builder()
                .index("*")
                .name(indexWriteAlias)
                .build();
        try {
            openSearchClient.indices().getAlias(getAliasRequest);
            log.debug("Write alias '{}' already exists, skipping physical index creation", indexWriteAlias);
        } catch (OpenSearchException e) {
            if (e.status() == 404) {
                createInitialPhysicalIndex(indexWriteAlias);
            } else {
                throw e;
            }
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.physicalIndexSetupFailed(indexWriteAlias, e);
        }
    }

    private void createInitialPhysicalIndex(String indexWriteAlias) {
        String physicalIndexName = IndexNaming.initialPhysicalIndexName(indexWriteAlias);
        log.info("Write alias '{}' does not exist — creating initial physical index '{}'", indexWriteAlias, physicalIndexName);
        try {
            // The write alias is set explicitly here so ISM can manage it exclusively during rollover.
            // If the write alias were in the template, ISM rollover would try to apply it again to the
            // new index while it still points to the old one, causing a "duplicated alias" pre-flight error.
            // The read alias and ISM rollover alias setting are applied automatically from the IaC template.
            openSearchClient.indices().create(CreateIndexRequest.of(r -> r
                    .index(physicalIndexName)
                    .aliases(Map.of(indexWriteAlias, new Alias.Builder().isWriteIndex(true).build()))));
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.physicalIndexSetupFailed(indexWriteAlias, e);
        }
        log.info("Created initial physical index '{}' with write alias '{}'", physicalIndexName, indexWriteAlias);
    }
}
