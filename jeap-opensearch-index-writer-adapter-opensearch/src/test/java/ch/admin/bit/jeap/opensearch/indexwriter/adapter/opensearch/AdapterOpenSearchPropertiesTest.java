package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterOpenSearchPropertiesTest {

    @Test
    void validate_awsSigningWithUrlWithoutScheme_defaultsToHttps() {
        AdapterOpenSearchProperties properties = properties("my-domain.eu-central-2.es.amazonaws.com", "eu-central-2");

        properties.validate();

        assertThat(properties.connectionUrl().hostAndPort()).isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
        assertThat(properties.connectionUrl().isHttps()).isTrue();
    }

    @Test
    void validate_awsSigningWithHttpsUrl_stripsSchemeForTransport() {
        AdapterOpenSearchProperties properties = properties("https://my-domain.eu-central-2.es.amazonaws.com", "eu-central-2");

        properties.validate();

        assertThat(properties.connectionUrl().hostAndPort()).isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
    }

    @Test
    void validate_awsSigningWithHttpUrl_fails() {
        AdapterOpenSearchProperties properties = properties("http://my-domain.eu-central-2.es.amazonaws.com", "eu-central-2");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("'https' is required")
                .hasMessageContaining("signing-region");
    }

    @Test
    void validate_withoutAwsSigning_httpUrlIsAllowed() {
        AdapterOpenSearchProperties properties = properties("http://localhost:9200", null);

        properties.validate();

        assertThat(properties.connectionUrl().toUrl()).isEqualTo("http://localhost:9200");
    }

    @Test
    void validate_blankSigningRegion_isTreatedAsNonAws() {
        AdapterOpenSearchProperties properties = properties("http://localhost:9200", "  ");

        properties.validate();

        assertThat(properties.connectionUrl().isHttps()).isFalse();
    }

    @Test
    void validate_missingUrl_fails() {
        AdapterOpenSearchProperties properties = properties(null, "eu-central-2");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("jeap.opensearch.indexwriter.connection.url");
    }

    private static AdapterOpenSearchProperties properties(String url, String signingRegion) {
        AdapterOpenSearchProperties properties = new AdapterOpenSearchProperties();
        properties.setUrl(url);
        properties.setSigningRegion(signingRegion);
        return properties;
    }
}
