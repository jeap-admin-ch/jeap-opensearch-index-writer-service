package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.connection")
@Slf4j
public class AdapterOpenSearchProperties {

    /**
     * URL of the OpenSearch cluster, i.e. {@code https://my-domain.eu-central-2.es.amazonaws.com} or
     * {@code https://my-opensearch-host:9200}. A URL without a scheme is interpreted as {@code https}.
     */
    private String url;

    /**
     * AWS region for SigV4 request signing (e.g. {@code eu-central-2}).
     * When set, requests to AWS OpenSearch Service are signed using the default AWS credential provider chain
     * (ECS task role, EC2 instance profile, environment variables, etc.) and the URL must use {@code https}.
     * Leave blank for non-AWS deployments.
     */
    private String signingRegion;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private OpenSearchConnectionUrl connectionUrl;

    @PostConstruct
    void validate() {
        connectionUrl = OpenSearchConnectionUrl.parse(url);
        if (isAwsSigningEnabled() && !connectionUrl.isHttps()) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(url,
                    "'https' is required when 'jeap.opensearch.indexwriter.connection.signing-region' is set, as AWS SigV4 signed requests are always sent over https");
        }
        log.info("Initialized AdapterOpenSearchProperties with url: {} (configured: {}), signingRegion: {}",
                connectionUrl.toUrl(), url, signingRegion);
    }

    OpenSearchConnectionUrl connectionUrl() {
        return connectionUrl;
    }

    private boolean isAwsSigningEnabled() {
        return signingRegion != null && !signingRegion.isBlank();
    }
}
