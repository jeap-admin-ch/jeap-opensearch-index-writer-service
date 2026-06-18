package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter")
public class IndexWriterProperties {

    private static final String DEFAULT_KEY = "default";

    private Map<String, IndexTemplateSettings> indexTemplates = new HashMap<>();

    IndexTemplateSettings settingsFor(String indexWriteAlias) {
        String templateName = IndexNaming.logicalName(indexWriteAlias);

        IndexTemplateSettings settings = indexTemplates.get(templateName);
        if (settings == null) {
            settings = indexTemplates.get(DEFAULT_KEY);
        }
        if (settings == null) {
            throw OpenSearchIndexWriterException.missingTemplateSettings(templateName);
        }

        validate(settings, templateName);

        return settings;
    }

    private static void validate(IndexTemplateSettings settings, String templateName) {
        List<String> missing = new ArrayList<>();
        if (settings.getNumberOfShards() == null) {
            missing.add("number-of-shards");
        }
        if (settings.getNumberOfReplicas() == null) {
            missing.add("number-of-replicas");
        }
        if (settings.getRefreshInterval() == null) {
            missing.add("refresh-interval");
        }
        if (!missing.isEmpty()) {
            throw OpenSearchIndexWriterException.incompleteTemplateSettings(templateName, missing);
        }
    }
}
