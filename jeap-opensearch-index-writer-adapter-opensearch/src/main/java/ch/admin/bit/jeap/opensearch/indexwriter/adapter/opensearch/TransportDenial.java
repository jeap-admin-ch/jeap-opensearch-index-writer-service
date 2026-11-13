package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.transport.httpclient5.ResponseException;

import java.util.OptionalInt;

// The transports throw instead of returning a response for denied requests: ApacheHttpClient5Transport a checked
// ResponseException for 401 and 403, AwsSdk2Transport an unchecked OpenSearchException for 403 even on generic()
final class TransportDenial {

    private TransportDenial() {
    }

    static OptionalInt deniedStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            int status = switch (t) {
                case ResponseException r -> r.status();
                case OpenSearchException o -> o.status();
                default -> 0;
            };
            if (status == 401 || status == 403) {
                return OptionalInt.of(status);
            }
        }
        return OptionalInt.empty();
    }
}
