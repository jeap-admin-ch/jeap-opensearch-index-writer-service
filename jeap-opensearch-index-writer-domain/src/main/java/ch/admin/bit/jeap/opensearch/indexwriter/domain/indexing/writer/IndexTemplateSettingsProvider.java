package ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer;

@FunctionalInterface
public interface IndexTemplateSettingsProvider {

    IndexTemplateSettings getSettings(String indexWriteAlias);
}
