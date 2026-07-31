package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

record OpenSearchConnectionUrl(String scheme, String host, int port) {

    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    static final int NO_PORT = -1;

    static OpenSearchConnectionUrl parse(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl, "must not be empty");
        }
        String trimmed = configuredUrl.trim();
        // A missing scheme defaults to https, so that a bare host name never results in an unencrypted connection
        String withScheme = trimmed.contains("://") ? trimmed : HTTPS + "://" + trimmed;

        URI uri;
        try {
            uri = new URI(withScheme);
        } catch (URISyntaxException e) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl, e.getReason());
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTPS.equals(scheme) && !HTTP.equals(scheme)) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl,
                    "unsupported scheme '%s', expected 'https' or 'http'".formatted(uri.getScheme()));
        }
        if (uri.getHost() == null) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl, "no host name found");
        }
        if (uri.getUserInfo() != null) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl, "credentials in the URL are not supported");
        }
        // A path is silently ignored by the OpenSearch clients, rejecting it avoids connecting to an unexpected endpoint
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl,
                    "a path is not supported, expected host name and optional port only");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw OpenSearchIndexWriterException.invalidConnectionUrl(configuredUrl,
                    "a query or fragment is not supported, expected host name and optional port only");
        }

        return new OpenSearchConnectionUrl(scheme, uri.getHost(), uri.getPort());
    }

    boolean isHttps() {
        return HTTPS.equals(scheme);
    }

    String hostAndPort() {
        return port == NO_PORT ? host : host + ":" + port;
    }

    String toUrl() {
        return scheme + "://" + hostAndPort();
    }
}
