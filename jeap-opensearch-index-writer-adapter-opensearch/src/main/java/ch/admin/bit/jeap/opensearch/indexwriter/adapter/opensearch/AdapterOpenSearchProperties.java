package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.opensearch")
@Slf4j
public class AdapterOpenSearchProperties {
    // others may come later
    private ConnectionType connectionType = ConnectionType.AWS;
    private Aws aws;

    @PostConstruct
    void log() {
        log.info("Initialized AdapterOpenSearchProperties with connectionType: {} and aws: {}", connectionType, aws);
    }

    @Data
    public static class Aws {
        private String endpoint;
        private String region;
    }

    public enum ConnectionType {
        AWS
    }
}
