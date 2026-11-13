package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNameInvalidException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotConcreteException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotFoundException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetPolicyCheckFailedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.AppliedPolicyInspection;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorResponse;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IsmAppliedPolicyInspectorTest {

    private static final String TARGET = "jme_decree_document_v1-000001";

    @Mock
    private OpenSearchClient client;
    @Mock
    private OpenSearchGenericClient genericClient;
    @Mock
    private Response response;
    @Mock
    private Response headResponse;

    private IsmAppliedPolicyInspector inspector;

    @BeforeEach
    void setUp() {
        inspector = new IsmAppliedPolicyInspector(client, JsonMapper.builder().build());
    }

    @Test
    void findsRolloverInLaterStateAndRequestsAppliedPolicy() throws IOException {
        respond(200, explain("""
                "policy_id":"rollover_policy","policy":{"states":[
                  {"name":"hot","actions":[],"transitions":[{"state_name":"warm"}]},
                  {"name":"warm","actions":[{"retry":{"count":3},"rollover":{"min_doc_count":5}}]}]}"""));

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.ROLLOVER_IN_POLICY);
        assertThat(inspection.policyId()).isEqualTo("rollover_policy");
        assertThat(inspection.rolloverStates()).containsExactly("warm");
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(genericClient).execute(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo("GET");
        assertThat(request.getValue().getEndpoint()).isEqualTo("/_plugins/_ism/explain/" + TARGET);
        assertThat(request.getValue().getParameters()).containsEntry("show_policy", "true");
        verify(response).close();
    }

    @Test
    void policyWithoutRolloverIsAllowed() throws IOException {
        respond(200, explain("""
                "policy_id":"replicas_policy","policy":{"states":[
                  {"name":"hot","actions":[{"retry":{"count":3},"replica_count":{"number_of_replicas":0}}]}]}"""));

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.NO_ROLLOVER_IN_POLICY);
        assertThat(inspection.policyId()).isEqualTo("replicas_policy");
    }

    @Test
    void noPolicyIdMeansNoPolicyApplied() throws IOException {
        respond(200, explain("""
                "index.plugins.index_state_management.policy_id":null,"index.opendistro.index_state_management.policy_id":null,"enabled":null"""));

        assertThat(inspector.inspect(TARGET).outcome()).isEqualTo(PolicyCheckOutcome.NO_POLICY_APPLIED);
    }

    @Test
    void policyIdFromSettingWithoutDefinitionIsUndetermined() throws IOException {
        respond(200, explain("""
                "index.plugins.index_state_management.policy_id":"pending_policy\""""));

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.policyId()).isEqualTo("pending_policy");
        assertThat(inspection.detail()).contains("does not report its applied definition");
    }

    @Test
    void aliasTargetIsRejectedInsteadOfAllowed() throws IOException {
        String alias = "jme_decree_document_v1_write";
        respond(200, "{\"%s\":{\"policy_id\":\"rollover_policy\"},\"total_managed_indices\":1}".formatted(TARGET));

        assertThatThrownBy(() -> inspector.inspect(alias))
                .isInstanceOfSatisfying(JobTargetNotConcreteException.class, e -> {
                    assertThat(e).hasMessageContaining(alias).hasMessageContaining("not a concrete index").hasMessageContaining(TARGET);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void responseWithoutTargetEntryOnExistingTargetIsUndetermined() throws IOException {
        respond(200, "{\"total_managed_indices\":0}");
        headStatus(200);

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).isEqualTo("the ISM explain response has no entry for the target");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400, 403})
    void aliasTargetIsRejectedWhenExplainCannotShowIt(int explainStatus) throws IOException {
        String alias = "jme_decree_document_v1_write";
        respond(explainStatus, explainStatus == 200 ? "{\"total_managed_indices\":0}" : "{\"error\":\"rejected\"}");
        headStatus(200, "{\"%s\":{\"settings\":{\"index\":{\"uuid\":\"abc\"}}}}".formatted(TARGET));

        assertThatThrownBy(() -> inspector.inspect(alias))
                .isInstanceOfSatisfying(JobTargetNotConcreteException.class, e -> {
                    assertThat(e).hasMessageContaining(alias).hasMessageContaining("not a concrete index").hasMessageContaining(TARGET);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void responseWithoutTargetEntryOnMissingTargetThrows() throws IOException {
        respond(200, "{\"total_managed_indices\":0}");
        headStatus(404);

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetNotFoundException.class, e -> {
                    assertThat(e).hasMessageContaining("does not exist");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void invalidJsonIsUndetermined() throws IOException {
        respond(200, "not json");

        assertThat(inspector.inspect(TARGET).outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
    }

    @Test
    void forbiddenExplainOnExistingTargetIsUndetermined() throws IOException {
        respond(403, "{\"error\":\"no permissions for [cluster:admin/opendistro/ism/managedindex/explain]\"}");
        headStatus(200);

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).contains("HTTP 403");
        ArgumentCaptor<Request> requests = ArgumentCaptor.forClass(Request.class);
        verify(genericClient, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues().get(1).getMethod()).isEqualTo("GET");
        assertThat(requests.getAllValues().get(1).getEndpoint()).isEqualTo("/" + TARGET);
        assertThat(requests.getAllValues().get(1).getParameters()).containsEntry("filter_path", "*.settings.index.uuid");
        verify(headResponse).close();
    }

    @Test
    void unauthorizedExplainIsNonRetryableFailureNotUndetermined() throws IOException {
        respond(401, "{\"error\":\"Unauthorized\"}");

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> {
                    assertThat(e).hasMessageContaining("HTTP 401").hasMessageContaining("credentials were rejected");
                    assertThat(e.getErrorCode()).isEqualTo(JobTargetPolicyCheckFailedException.ERROR_CODE);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void unauthorizedTargetCheckIsNonRetryableFailure() throws IOException {
        respond(400, "{}");
        headStatus(401);

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> {
                    assertThat(e).hasMessageContaining("target check returned HTTP 401");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void forbiddenTargetCheckIsUndeterminedNotMissing() throws IOException {
        respond(403, "{\"error\":\"no permissions\"}");
        headStatus(403);

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).contains("the target could not be checked: HTTP 403");
    }

    @Test
    void awsForbiddenExplainOnExistingTargetIsUndetermined() throws IOException {
        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any())).thenThrow(awsForbidden()).thenReturn(headResponse);
        when(headResponse.getStatus()).thenReturn(200);
        when(headResponse.getBody()).thenReturn(Optional.of(Body.from(
                "{\"%s\":{\"settings\":{\"index\":{\"uuid\":\"abc\"}}}}".formatted(TARGET).getBytes(StandardCharsets.UTF_8), "application/json")));

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).contains("HTTP 403");
    }

    @Test
    void awsForbiddenExplainAndTargetCheckIsUndetermined() throws IOException {
        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any())).thenThrow(awsForbidden(), awsForbidden());

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).contains("the target could not be checked: HTTP 403");
    }

    @Test
    void awsForbiddenTargetCheckAfterRejectedExplainIsUndetermined() throws IOException {
        respond(400, "{}");
        when(genericClient.execute(any())).thenReturn(response).thenThrow(awsForbidden());

        AppliedPolicyInspection inspection = inspector.inspect(TARGET);

        assertThat(inspection.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(inspection.detail()).contains("the target could not be checked: HTTP 403");
    }

    @Test
    void otherOpenSearchExceptionIsRetryableFailure() throws IOException {
        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any())).thenThrow(openSearchException(400));

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void unavailableTargetCheckIsRetryableFailure() throws IOException {
        respond(400, "{}");
        headStatus(503);

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void rejectedExplainOnMissingTargetThrows() throws IOException {
        respond(400, "{\"error\":\"no documents to get\"}");
        headStatus(404);

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetNotFoundException.class, e -> {
                    assertThat(e).hasMessageContaining(TARGET).hasMessageContaining("does not exist");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void serverErrorsAreRetryableFailures(int status) throws IOException {
        respond(status, "{}");

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> {
                    assertThat(e).hasMessageContaining("HTTP " + status);
                    assertThat(e.isRetryable()).isTrue();
                });
        verify(genericClient, times(1)).execute(any());
    }

    @Test
    void connectionFailureIsRetryableFailure() throws IOException {
        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any())).thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> inspector.inspect(TARGET))
                .isInstanceOfSatisfying(JobTargetPolicyCheckFailedException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "jme_*", "a,b", "_all", "-index", "a/b", "a b", "a#b", "jme%2A", "Jme_index-000001", "a[b]", "a{b}", "a^b", "a`b", ".hidden", "ä-000001", "a:b"})
    void rejectsNamesThatAreNotOneConcreteIndex(String name) {
        assertThatThrownBy(() -> inspector.inspect(name))
                .isInstanceOfSatisfying(JobTargetNameInvalidException.class, e -> {
                    assertThat(e).hasMessageContaining("Not a concrete index name");
                    assertThat(e.isRetryable()).isFalse();
                });
        verifyNoInteractions(client);
    }

    @Test
    void rejectsNamesLongerThan255Characters() {
        String tooLongName = "a".repeat(256);

        assertThatThrownBy(() -> inspector.inspect(tooLongName))
                .isInstanceOf(JobTargetNameInvalidException.class);
        verifyNoInteractions(client);
    }

    private void respond(int status, String body) throws IOException {
        when(client.generic()).thenReturn(genericClient);
        when(genericClient.execute(any())).thenReturn(response);
        when(response.getStatus()).thenReturn(status);
        when(response.getBody()).thenReturn(Optional.of(Body.from(body.getBytes(StandardCharsets.UTF_8), "application/json")));
    }

    private void headStatus(int status) throws IOException {
        headStatus(status, "{\"%s\":{\"settings\":{\"index\":{\"uuid\":\"abc\"}}}}".formatted(TARGET));
    }

    private void headStatus(int status, String body) throws IOException {
        when(genericClient.execute(any())).thenReturn(response, headResponse);
        when(headResponse.getStatus()).thenReturn(status);
        if (status == 200) {
            when(headResponse.getBody()).thenReturn(Optional.of(Body.from(body.getBytes(StandardCharsets.UTF_8), "application/json")));
        }
    }

    static OpenSearchException awsForbidden() {
        return openSearchException(403);
    }

    private static OpenSearchException openSearchException(int status) {
        return new OpenSearchException(ErrorResponse.of(e -> e.status(status)
                .error(c -> c.type("security_exception").reason("authentication/authorization failure"))));
    }

    private static String explain(String indexFields) {
        return "{\"%s\":{%s},\"total_managed_indices\":1}".formatted(TARGET, indexFields);
    }
}
