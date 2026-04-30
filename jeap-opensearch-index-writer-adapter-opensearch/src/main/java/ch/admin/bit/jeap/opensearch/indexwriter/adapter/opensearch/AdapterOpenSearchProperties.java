package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.connection")
@Slf4j
public class AdapterOpenSearchProperties {

    private String url;

    /**
     * AWS region for SigV4 request signing (e.g. {@code eu-central-2}).
     * When set, requests to AWS OpenSearch Service are signed using the default AWS credential provider chain
     * (ECS task role, EC2 instance profile, environment variables, etc.).
     * Leave blank for non-AWS deployments.
     */
    private String signingRegion;

    @PostConstruct
    void log() {
        log.info("Initialized AdapterOpenSearchProperties with url: {}, signingRegion: {}", url, signingRegion);
    }
}
