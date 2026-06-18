package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
class IndexTemplateManager {

    private static final String ISM_ROLLOVER_ALIAS_SETTING = "plugins.index_state_management.rollover_alias";

    private final OpenSearchClient openSearchClient;

    void ensureIndexTemplateUpToDate(String indexWriteAlias, String indexReadAlias, int minorVersion, TypeMapping typeMapping, IndexTemplateSettings templateSettings) {
        String name = IndexNaming.logicalName(indexWriteAlias);
        log.debug("Applying index template '{}' version {} ({} shards, {} replicas, refresh '{}')",
                name, minorVersion, templateSettings.getNumberOfShards(), templateSettings.getNumberOfReplicas(), templateSettings.getRefreshInterval());
        IndexSettings settings = IndexSettings.of(s -> s
                .numberOfShards(templateSettings.getNumberOfShards())
                .numberOfReplicas(templateSettings.getNumberOfReplicas())
                .refreshInterval(t -> t.time(templateSettings.getRefreshInterval()))
                .customSettings(ISM_ROLLOVER_ALIAS_SETTING, JsonData.of(indexWriteAlias)));
        PutIndexTemplateRequest putRequest = new PutIndexTemplateRequest.Builder()
                .name(name)
                .indexPatterns(List.of(IndexNaming.indexPattern(indexWriteAlias)))
                .template(t -> t
                        .settings(settings)
                        // Write alias must NOT be in the template — ISM rollover creates the new partition from
                        // the template, and if the write alias were already there it would point to both old and
                        // new partitions simultaneously, triggering "Rollover alias can point to multiple indices".
                        .aliases(Map.of(indexReadAlias, new Alias.Builder().build()))
                        .mappings(typeMapping))
                .version((long) minorVersion)
                .build();
        try {
            openSearchClient.indices().putIndexTemplate(putRequest);
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.templateSetupFailed(indexWriteAlias, e);
        }
    }
}
