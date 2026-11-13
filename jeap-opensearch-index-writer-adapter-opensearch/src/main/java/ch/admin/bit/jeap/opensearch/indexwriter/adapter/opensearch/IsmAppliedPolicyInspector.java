package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.AppliedPolicyInspection;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.AppliedPolicyInspector;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNameInvalidException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotConcreteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetPolicyCheckFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
class IsmAppliedPolicyInspector implements AppliedPolicyInspector {

    private static final String POLICY_ID_SETTING = "index.plugins.index_state_management.policy_id";
    private static final String LEGACY_POLICY_ID_SETTING = "index.opendistro.index_state_management.policy_id";
    // Only the characters IndexNaming produces: anything else could address other indices or is rejected by the
    // request URI builder when appended to the path, and aliases or system indices are not valid targets anyway
    private static final Pattern CONCRETE_INDEX_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,254}");

    private final OpenSearchClient openSearchClient;
    private final JsonMapper jsonMapper;

    @Override
    public AppliedPolicyInspection inspect(String targetIndex) {
        if (targetIndex == null || !CONCRETE_INDEX_NAME.matcher(targetIndex).matches()) {
            throw new JobTargetNameInvalidException(targetIndex);
        }
        try (Response response = explain(targetIndex)) {
            int status = response.getStatus();
            String body = response.getBody().map(Body::bodyAsString).orElse("");
            if (status == 429 || status >= 500) {
                throw new JobTargetPolicyCheckFailedException(targetIndex, "HTTP %d: %s".formatted(status, body));
            }
            if (status != 200) {
                return explainRejected(targetIndex, status, body);
            }
            return inspectExplainResponse(targetIndex, body);
        } catch (IllegalArgumentException _) {
            throw new JobTargetNameInvalidException(targetIndex);
        } catch (IOException | OpenSearchException e) {
            return explainFailed(targetIndex, e);
        }
    }

    private AppliedPolicyInspection explainFailed(String targetIndex, Exception e) {
        OptionalInt deniedStatus = TransportDenial.deniedStatus(e);
        if (deniedStatus.isEmpty()) {
            throw new JobTargetPolicyCheckFailedException(targetIndex, e);
        }
        try {
            return explainRejected(targetIndex, deniedStatus.getAsInt(), "access denied");
        } catch (IOException | OpenSearchException lookupFailure) {
            throw new JobTargetPolicyCheckFailedException(targetIndex, lookupFailure);
        }
    }

    private Response explain(String targetIndex) throws IOException {
        return openSearchClient.generic().execute(Requests.builder()
                .method("GET")
                .endpoint("/_plugins/_ism/explain/" + targetIndex)
                .query(Map.of("show_policy", "true"))
                .build());
    }

    // Explain answers 400 for a missing index, as it does for some ISM failures; only treat the policy as
    // unreadable if the target exists, so a missing target keeps its ordinary error handling
    private AppliedPolicyInspection explainRejected(String targetIndex, int status, String body) throws IOException {
        if (status == 401) {
            throw JobTargetPolicyCheckFailedException.credentialsRejected(targetIndex, "ISM explain returned HTTP 401");
        }
        if (status == 403) {
            log.error("ISM explain for '{}' was denied (HTTP 403); the maintenance policy check is not effective until the service principal "
                    + "is granted the cluster permission cluster:admin/opendistro/ism/managedindex/explain", targetIndex);
        }
        return undeterminedIfTargetExists(targetIndex, "ISM explain returned HTTP %d".formatted(status), ": " + body);
    }

    private AppliedPolicyInspection undeterminedIfTargetExists(String targetIndex, String reason, String detail) throws IOException {
        // Read by status: the typed exists() maps every 4xx, including 403, to "does not exist". GET instead of HEAD,
        // because only the response keys show whether the target is a concrete index or an alias resolving to others.
        int lookupStatus;
        String lookupBody = "";
        try (Response lookup = openSearchClient.generic().execute(Requests.builder()
                .method("GET")
                .endpoint("/" + targetIndex)
                .query(Map.of("filter_path", "*.settings.index.uuid"))
                .build())) {
            lookupStatus = lookup.getStatus();
            if (lookupStatus == 200) {
                lookupBody = lookup.getBody().map(Body::bodyAsString).orElse("");
            }
        } catch (IOException e) {
            lookupStatus = TransportDenial.deniedStatus(e).orElseThrow(() -> e);
        } catch (OpenSearchException e) {
            lookupStatus = TransportDenial.deniedStatus(e).orElseThrow(() -> e);
        }
        if (lookupStatus == 404) {
            throw new JobTargetNotFoundException(targetIndex);
        }
        if (lookupStatus == 401) {
            throw JobTargetPolicyCheckFailedException.credentialsRejected(targetIndex, "%s and the target check returned HTTP 401".formatted(reason));
        }
        if (lookupStatus == 429 || lookupStatus >= 500) {
            throw new JobTargetPolicyCheckFailedException(targetIndex,
                    "%s and the target check HTTP %d".formatted(reason, lookupStatus));
        }
        if (lookupStatus == 200) {
            rejectIfNotConcrete(targetIndex, lookupBody);
        }
        String targetCheck = lookupStatus == 200 ? "" : " (the target could not be checked: HTTP %d)".formatted(lookupStatus);
        return undetermined(targetIndex, null, reason + targetCheck + detail);
    }

    private void rejectIfNotConcrete(String targetIndex, String body) {
        try {
            Set<String> resolvedIndices = resolvedIndices(jsonMapper.readTree(body));
            if (!resolvedIndices.isEmpty() && !resolvedIndices.equals(Set.of(targetIndex))) {
                throw new JobTargetNotConcreteException(targetIndex, resolvedIndices);
            }
        } catch (JacksonException _) {
            // an unreadable lookup body proves nothing about the target, which exists
        }
    }

    private AppliedPolicyInspection inspectExplainResponse(String targetIndex, String body) throws IOException {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException _) {
            return undetermined(targetIndex, null, "the ISM explain response is not valid JSON");
        }
        JsonNode index = root.path(targetIndex);
        if (!index.isObject()) {
            // Explain resolves aliases and answers per concrete index, so other entries mean the target is an alias.
            // Treating it as undetermined would allow exactly the write aliases that ISM rolls over.
            Set<String> resolvedIndices = resolvedIndices(root);
            if (!resolvedIndices.isEmpty()) {
                throw new JobTargetNotConcreteException(targetIndex, resolvedIndices);
            }
            return undeterminedIfTargetExists(targetIndex, "the ISM explain response has no entry for the target", "");
        }
        String policyId = policyId(index);
        if (policyId == null) {
            return AppliedPolicyInspection.noPolicyApplied();
        }
        JsonNode states = index.path("policy").path("states");
        if (!states.isArray()) {
            return undetermined(targetIndex, policyId, "policy '%s' is assigned but ISM does not report its applied definition"
                    .formatted(policyId));
        }
        List<String> rolloverStates = rolloverStates(states);
        return rolloverStates.isEmpty()
                ? AppliedPolicyInspection.noRollover(policyId)
                : AppliedPolicyInspection.rollover(policyId, rolloverStates);
    }

    private static Set<String> resolvedIndices(JsonNode root) {
        return root.properties().stream()
                .filter(entry -> entry.getValue().isObject())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static String policyId(JsonNode index) {
        for (String field : List.of("policy_id", POLICY_ID_SETTING, LEGACY_POLICY_ID_SETTING)) {
            JsonNode value = index.path(field);
            if (value.isString() && !value.stringValue().isBlank()) {
                return value.stringValue();
            }
        }
        return null;
    }

    // Every state is inspected, not only the current one, so a rollover in a later state is also found
    private static List<String> rolloverStates(JsonNode states) {
        List<String> rolloverStates = new ArrayList<>();
        for (JsonNode state : states) {
            for (JsonNode action : state.path("actions")) {
                if (action.has("rollover")) {
                    rolloverStates.add(state.path("name").asString("?"));
                    break;
                }
            }
        }
        return rolloverStates;
    }

    private static AppliedPolicyInspection undetermined(String targetIndex, String policyId, String detail) {
        log.warn("Could not determine the ISM policy applied to '{}': {}", targetIndex, detail);
        return AppliedPolicyInspection.undetermined(policyId, detail);
    }
}
