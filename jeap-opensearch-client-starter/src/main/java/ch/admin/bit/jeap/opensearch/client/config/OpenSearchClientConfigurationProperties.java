package ch.admin.bit.jeap.opensearch.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jeap.opensearch.client.connection")
public class OpenSearchClientConfigurationProperties {

    /**
     * URI of the OpenSearch cluster, e.g. {@code http://localhost:9200} or an AWS OpenSearch endpoint
     * (with or without a scheme, depending on the transport). Required property.
     */
    private String uri;

    /**
     * AWS region for SigV4 request signing (e.g. {@code eu-central-2}). If set, the
     * AwsSdk2Transport is enabled and requests are signed using the default AWS credentials provider chain.
     * Leave empty for non-AWS deployments.
     */
    private String awsSigningRegion;
}
