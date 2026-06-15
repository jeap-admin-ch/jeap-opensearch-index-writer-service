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
import org.opensearch.client.opensearch.indices.IndexTemplate;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.opensearch.client.opensearch.indices.get_index_template.IndexTemplateItem;
import org.opensearch.client.opensearch.indices.IndexTemplateSummary;
import org.opensearch.client.opensearch.indices.put_index_template.IndexTemplateMapping;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
class IndexTemplateManager {

    private static final String WRITE_ALIAS_SUFFIX = "_write";
    private static final String INDEX_PATTERN_SUFFIX = "-*";
    private static final String ISM_ROLLOVER_ALIAS_SETTING = "plugins.index_state_management.rollover_alias";

    private final OpenSearchClient openSearchClient;

    void ensureIndexTemplateUpToDate(String indexWriteAlias, String indexReadAlias, int minorVersion, TypeMapping typeMapping) {
        String templateName = templateName(indexWriteAlias);

        GetIndexTemplateRequest getRequest = new GetIndexTemplateRequest.Builder().name(templateName).build();
        GetIndexTemplateResponse getResponse;
        try {
            getResponse = openSearchClient.indices().getIndexTemplate(getRequest);
        } catch (OpenSearchException e) {
            if (e.status() == 404) {
                throw OpenSearchIndexWriterException.indexTemplateNotFound(indexWriteAlias);
            }
            throw e;
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.templateSetupFailed(indexWriteAlias, e);
        }

        IndexTemplateItem existingItem = getResponse.indexTemplates().stream()
                .filter(i -> i.name().equals(templateName))
                .findFirst()
                .orElse(null);

        if (existingItem != null && Long.valueOf(minorVersion).equals(existingItem.indexTemplate().version())) {
            log.debug("Index template '{}' is already up-to-date at version {}", templateName, minorVersion);
            return;
        }

        // Template exists but mapping is outdated: preserve IaC-managed settings and aliases, only update mapping
        log.info("Updating mapping in index template '{}' at version {}", templateName, minorVersion);
        updateTemplateMapping(templateName, existingItem, indexWriteAlias, indexReadAlias, minorVersion, typeMapping);
    }

    private void updateTemplateMapping(String templateName, IndexTemplateItem existingItem, String indexWriteAlias, String indexReadAlias, int minorVersion, TypeMapping typeMapping) {
        IndexTemplate existing = existingItem != null ? existingItem.indexTemplate() : null;
        IndexTemplateSummary existingTpl = existing != null ? existing.template() : null;

        List<String> indexPatterns = existing != null && !existing.indexPatterns().isEmpty()
                ? existing.indexPatterns()
                : List.of(indexPattern(indexWriteAlias));

        IndexTemplateMapping.Builder tplBuilder = new IndexTemplateMapping.Builder().mappings(typeMapping);
        if (existingTpl != null && existingTpl.settings() != null) {
            tplBuilder.settings(existingTpl.settings());
        } else {
            tplBuilder.settings(IndexSettings.of(s -> s.customSettings(ISM_ROLLOVER_ALIAS_SETTING, JsonData.of(indexWriteAlias))));
        }
        if (existingTpl != null && !existingTpl.aliases().isEmpty()) {
            tplBuilder.aliases(existingTpl.aliases());
        } else {
            // Fallback: only the read alias goes in the template. The write alias must NOT be in the
            // template because ISM rollover creates the new index from the template — if the write alias
            // were already there, it would point to both old and new indices simultaneously, triggering
            // OpenSearch's "Rollover alias can point to multiple indices, found duplicated alias" error.
            tplBuilder.aliases(Map.of(indexReadAlias, new Alias.Builder().build()));
        }

        PutIndexTemplateRequest putRequest = new PutIndexTemplateRequest.Builder()
                .name(templateName)
                .indexPatterns(indexPatterns)
                .template(tplBuilder.build())
                .version((long) minorVersion)
                .build();
        try {
            openSearchClient.indices().putIndexTemplate(putRequest);
        } catch (IOException e) {
            throw OpenSearchIndexWriterException.templateSetupFailed(indexWriteAlias, e);
        }
    }

    static String templateName(String indexWriteAlias) {
        String base = indexWriteAlias.toLowerCase();
        if (base.endsWith(WRITE_ALIAS_SUFFIX)) {
            base = base.substring(0, base.length() - WRITE_ALIAS_SUFFIX.length());
        }
        return base;
    }

    static String indexPattern(String indexWriteAlias) {
        String base = indexWriteAlias.toLowerCase();
        if (base.endsWith(WRITE_ALIAS_SUFFIX)) {
            base = base.substring(0, base.length() - WRITE_ALIAS_SUFFIX.length());
        }
        return base + INDEX_PATTERN_SUFFIX;
    }
}
