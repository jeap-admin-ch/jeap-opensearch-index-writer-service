package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchConnectionUrlTest {

    @Test
    void parse_httpsUrl() {
        OpenSearchConnectionUrl url = OpenSearchConnectionUrl.parse("https://my-domain.eu-central-2.es.amazonaws.com");

        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
        assertThat(url.port()).isEqualTo(OpenSearchConnectionUrl.NO_PORT);
        assertThat(url.hostAndPort()).isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
        assertThat(url.toUrl()).isEqualTo("https://my-domain.eu-central-2.es.amazonaws.com");
        assertThat(url.isHttps()).isTrue();
    }

    @Test
    void parse_hostWithoutScheme_defaultsToHttps() {
        OpenSearchConnectionUrl url = OpenSearchConnectionUrl.parse("my-domain.eu-central-2.es.amazonaws.com");

        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.hostAndPort()).isEqualTo("my-domain.eu-central-2.es.amazonaws.com");
        assertThat(url.isHttps()).isTrue();
    }

    @Test
    void parse_hostAndPortWithoutScheme_defaultsToHttps() {
        OpenSearchConnectionUrl url = OpenSearchConnectionUrl.parse("my-opensearch-host:9200");

        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("my-opensearch-host");
        assertThat(url.port()).isEqualTo(9200);
        assertThat(url.hostAndPort()).isEqualTo("my-opensearch-host:9200");
        assertThat(url.toUrl()).isEqualTo("https://my-opensearch-host:9200");
    }

    @Test
    void parse_httpUrl_keepsHttp() {
        OpenSearchConnectionUrl url = OpenSearchConnectionUrl.parse("http://localhost:9200");

        assertThat(url.scheme()).isEqualTo("http");
        assertThat(url.host()).isEqualTo("localhost");
        assertThat(url.port()).isEqualTo(9200);
        assertThat(url.isHttps()).isFalse();
        assertThat(url.toUrl()).isEqualTo("http://localhost:9200");
    }

    @Test
    void parse_uppercaseScheme_isNormalized() {
        assertThat(OpenSearchConnectionUrl.parse("HTTPS://my-host").scheme()).isEqualTo("https");
    }

    @Test
    void parse_trailingSlashAndSurroundingWhitespace_areIgnored() {
        OpenSearchConnectionUrl url = OpenSearchConnectionUrl.parse("  https://my-host:9200/  ");

        assertThat(url.toUrl()).isEqualTo("https://my-host:9200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void parse_blankUrl_fails(String configuredUrl) {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse(configuredUrl))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("jeap.opensearch.indexwriter.connection.url")
                .hasMessageContaining("must not be empty");
    }

    @Test
    void parse_nullUrl_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse(null))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void parse_unsupportedScheme_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse("ftp://my-host"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("unsupported scheme 'ftp'");
    }

    @Test
    void parse_duplicatedScheme_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse("https://https://my-host"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("jeap.opensearch.indexwriter.connection.url");
    }

    @Test
    void parse_urlWithPath_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse("https://my-host/opensearch"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("a path is not supported");
    }

    @Test
    void parse_urlWithCredentials_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse("https://user:secret@my-host"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("credentials in the URL are not supported");
    }

    @Test
    void parse_urlWithQuery_fails() {
        assertThatThrownBy(() -> OpenSearchConnectionUrl.parse("https://my-host?foo=bar"))
                .isInstanceOf(OpenSearchIndexWriterException.class)
                .hasMessageContaining("a query or fragment is not supported");
    }
}
