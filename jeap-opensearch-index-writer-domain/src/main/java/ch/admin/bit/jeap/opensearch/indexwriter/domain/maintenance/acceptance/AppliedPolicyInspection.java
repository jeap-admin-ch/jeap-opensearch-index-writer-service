package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import java.util.List;
import java.util.Objects;

public record AppliedPolicyInspection(PolicyCheckOutcome outcome, String policyId, List<String> rolloverStates, String detail) {

    public AppliedPolicyInspection {
        Objects.requireNonNull(outcome, "outcome");
        rolloverStates = rolloverStates == null ? List.of() : List.copyOf(rolloverStates);
    }

    public static AppliedPolicyInspection rollover(String policyId, List<String> rolloverStates) {
        return new AppliedPolicyInspection(PolicyCheckOutcome.ROLLOVER_IN_POLICY, policyId, rolloverStates, null);
    }

    public static AppliedPolicyInspection noRollover(String policyId) {
        return new AppliedPolicyInspection(PolicyCheckOutcome.NO_ROLLOVER_IN_POLICY, policyId, List.of(), null);
    }

    public static AppliedPolicyInspection noPolicyApplied() {
        return new AppliedPolicyInspection(PolicyCheckOutcome.NO_POLICY_APPLIED, null, List.of(), null);
    }

    public static AppliedPolicyInspection undetermined(String policyId, String detail) {
        return new AppliedPolicyInspection(PolicyCheckOutcome.POLICY_UNDETERMINED, policyId, List.of(), detail);
    }
}
