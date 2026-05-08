package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.Alias;
import org.opensearch.client.opensearch.indices.GetIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.GetIndexTemplateResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.put_index_template.IndexTemplateMapping;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
class IndexTemplateManager {

    private static final String WRITE_ALIAS_SUFFIX = "_write";
    private static final String TEMPLATE_NAME_SUFFIX = "_template";
    private static final String INDEX_PATTERN_SUFFIX = "-*";
    private static final String ISM_ROLLOVER_ALIAS_SETTING = "plugins.index_state_management.rollover_alias";

    private final OpenSearchClient openSearchClient;

    void ensureIndexTemplateUpToDate(String indexWriteAlias, String indexReadAlias, int minorVersion, TypeMapping typeMapping) throws IOException {
        String templateName = templateName(indexWriteAlias);
        if (isTemplateUpToDate(templateName, minorVersion)) {
            log.debug("Index template '{}' is already up-to-date at version {}", templateName, minorVersion);
            return;
        }
        String indexPattern = indexPattern(indexWriteAlias);
        log.info("Creating/updating index template '{}' with pattern '{}' at version {}", templateName, indexPattern, minorVersion);
        PutIndexTemplateRequest putRequest = new PutIndexTemplateRequest.Builder()
                .name(templateName)
                .indexPatterns(indexPattern)
                .template(new IndexTemplateMapping.Builder()
                        .mappings(typeMapping)
                        .aliases(Map.of(indexReadAlias, new Alias.Builder().build()))
                        .settings(IndexSettings.of(s -> s
                                .customSettings(ISM_ROLLOVER_ALIAS_SETTING, JsonData.of(indexWriteAlias))))
                        .build())
                .version((long) minorVersion)
                .build();
        openSearchClient.indices().putIndexTemplate(putRequest);
    }

    private boolean isTemplateUpToDate(String templateName, int minorVersion) throws IOException {
        GetIndexTemplateRequest getRequest = new GetIndexTemplateRequest.Builder()
                .name(templateName)
                .build();
        GetIndexTemplateResponse response;
        try {
            response = openSearchClient.indices().getIndexTemplate(getRequest);
        } catch (OpenSearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
        return response.indexTemplates().stream()
                .filter(item -> item.name().equals(templateName))
                .findFirst()
                .map(item -> Long.valueOf(minorVersion).equals(item.indexTemplate().version()))
                .orElse(false);
    }

    static String templateName(String indexWriteAlias) {
        return indexWriteAlias.toLowerCase() + TEMPLATE_NAME_SUFFIX;
    }

    static String indexPattern(String indexWriteAlias) {
        String base = indexWriteAlias.toLowerCase();
        if (base.endsWith(WRITE_ALIAS_SUFFIX)) {
            base = base.substring(0, base.length() - WRITE_ALIAS_SUFFIX.length());
        }
        return base + INDEX_PATTERN_SUFFIX;
    }
}
