package ch.admin.bit.jeap.opensearch.indexwriter.index.config.repository;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.index-writer")
public class IndexConfigRepositoryProperties {

    private String messagesLocation = "classpath:/opensearch/messages.json";
}
