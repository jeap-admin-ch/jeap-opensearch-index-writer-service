package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Opt-in: without the explain permission the policy check silently allows every target
@Component
@ConditionalOnProperty(prefix = "jeap.opensearch.indexwriter.maintenance.policy-check", name = "verify-permission-on-startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
class IsmExplainPermissionVerifier {

    // Does not exist: only the authorization of the call matters, not the answer for the index
    private static final String PROBE_INDEX = "jeap-policy-check-permission-probe";

    private final OpenSearchClient openSearchClient;

    @EventListener(ApplicationReadyEvent.class)
    void verifyOnStartup() {
        verify();
    }

    boolean verify() {
        int status;
        try (Response response = openSearchClient.generic().execute(Requests.builder()
                .method("GET")
                .endpoint("/_plugins/_ism/explain/" + PROBE_INDEX)
                .build())) {
            status = response.getStatus();
        } catch (IOException | RuntimeException e) {
            status = TransportDenial.deniedStatus(e).orElse(0);
            if (status == 0) {
                log.warn("Could not verify the ISM explain permission needed by the maintenance policy check", e);
                return true;
            }
        }
        if (status == 401 || status == 403) {
            log.error("ISM explain was denied (HTTP {}): the maintenance policy check is not effective and allows every target as undetermined "
                    + "until the service principal is granted the cluster permission cluster:admin/opendistro/ism/managedindex/explain", status);
            return false;
        }
        return true;
    }
}
