package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobTargetAcceptanceCheckTest {

    private static final String TARGET = "jme_decree_document_v1-000001";

    @Mock
    private AppliedPolicyInspector inspector;

    private SimpleMeterRegistry meterRegistry;
    private JobTargetAcceptanceCheck check;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        check = new JobTargetAcceptanceCheck(inspector, meterRegistry);
    }

    @Test
    void refusesRolloverPolicyWithTargetAndReason() {
        when(inspector.inspect(TARGET)).thenReturn(AppliedPolicyInspection.rollover("rollover_policy", List.of("warm")));

        JobTargetAcceptance acceptance = check.check(TARGET);

        assertThat(acceptance.allowed()).isFalse();
        assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.ROLLOVER_IN_POLICY);
        assertThat(acceptance.targetIndex()).isEqualTo(TARGET);
        assertThat(acceptance.policyId()).isEqualTo("rollover_policy");
        assertThat(acceptance.reason()).contains(TARGET, "rollover_policy", "[warm]", "rollover action");
        assertThatThrownBy(acceptance::requireAllowed)
                .isInstanceOfSatisfying(JobTargetNotAllowedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("not-allowed");
                    assertThat(e.getTargetIndex()).isEqualTo(TARGET);
                    assertThat(e.getReason()).isEqualTo(acceptance.reason());
                    assertThat(e.isRetryable()).isFalse();
                });
        assertThat(count("rollover_in_policy")).isEqualTo(1);
    }

    @Test
    void allowsPolicyWithoutRollover() {
        when(inspector.inspect(TARGET)).thenReturn(AppliedPolicyInspection.noRollover("replicas_policy"));

        JobTargetAcceptance acceptance = check.check(TARGET);

        assertThat(acceptance.allowed()).isTrue();
        assertThat(acceptance.requireAllowed()).isSameAs(acceptance);
        assertThat(acceptance.reason()).contains("replicas_policy", "no rollover action");
        assertThat(count("no_rollover_in_policy")).isEqualTo(1);
    }

    @Test
    void allowsTargetWithoutAppliedPolicy() {
        when(inspector.inspect(TARGET)).thenReturn(AppliedPolicyInspection.noPolicyApplied());

        JobTargetAcceptance acceptance = check.check(TARGET);

        assertThat(acceptance.allowed()).isTrue();
        assertThat(acceptance.policyId()).isNull();
        assertThat(acceptance.reason()).contains("No ISM policy");
        assertThat(count("no_policy_applied")).isEqualTo(1);
    }

    @Test
    void allowsUndeterminedPolicyWithoutClaimingVerification() {
        when(inspector.inspect(TARGET)).thenReturn(AppliedPolicyInspection.undetermined(null, "ISM explain returned HTTP 403"));

        JobTargetAcceptance acceptance = check.check(TARGET);

        assertThat(acceptance.allowed()).isTrue();
        assertThat(acceptance.outcome()).isEqualTo(PolicyCheckOutcome.POLICY_UNDETERMINED);
        assertThat(acceptance.reason()).contains("HTTP 403", "without verifying non-rolling operation");
        assertThat(count("policy_undetermined")).isEqualTo(1);
    }

    @Test
    void propagatesInspectorErrorsWithoutCounting() {
        RuntimeException outage = new IllegalStateException("OpenSearch unavailable");
        when(inspector.inspect(TARGET)).thenThrow(outage);

        assertThatThrownBy(() -> check.check(TARGET)).isSameAs(outage);
        assertThat(meterRegistry.find(JobTargetAcceptanceCheck.CHECK_METRICS_NAME).counters()).isEmpty();
    }

    private double count(String outcome) {
        return meterRegistry.get(JobTargetAcceptanceCheck.CHECK_METRICS_NAME).tag("outcome", outcome).counter().count();
    }
}
