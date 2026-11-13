package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;

// Best-effort, point-in-time check: call it once at job creation only. Execution and recovery must not repeat it,
// policy changes after acceptance are an accepted trade-off.
@Service
@RequiredArgsConstructor
@Slf4j
public class JobTargetAcceptanceCheck {

    static final String CHECK_METRICS_NAME = "jeap.opensearch.indexwriter.maintenance.policy.check";

    private final AppliedPolicyInspector appliedPolicyInspector;
    private final MeterRegistry meterRegistry;

    public JobTargetAcceptance check(String targetIndex) {
        Objects.requireNonNull(targetIndex, "targetIndex");
        AppliedPolicyInspection inspection = appliedPolicyInspector.inspect(targetIndex);
        JobTargetAcceptance acceptance = new JobTargetAcceptance(targetIndex, inspection.outcome(), inspection.policyId(),
                reason(targetIndex, inspection));
        count(inspection.outcome());
        if (acceptance.allowed()) {
            log.info("Maintenance job target '{}' allowed: {}", targetIndex, acceptance.reason());
        } else {
            log.warn("Maintenance job target '{}' refused: {}", targetIndex, acceptance.reason());
        }
        return acceptance;
    }

    private static String reason(String targetIndex, AppliedPolicyInspection inspection) {
        return switch (inspection.outcome()) {
            case ROLLOVER_IN_POLICY -> ("Target index '%s' has the applied ISM policy '%s' with a rollover action in state(s) %s; " +
                    "maintenance jobs are not allowed on a rolling index").formatted(targetIndex, inspection.policyId(), inspection.rolloverStates());
            case NO_ROLLOVER_IN_POLICY -> "The applied ISM policy '%s' of target index '%s' has no rollover action"
                    .formatted(inspection.policyId(), targetIndex);
            case NO_POLICY_APPLIED -> "No ISM policy is reported as applied to target index '%s'".formatted(targetIndex);
            case POLICY_UNDETERMINED -> ("The ISM policy applied to target index '%s' could not be determined (%s); " +
                    "proceeding without verifying non-rolling operation").formatted(targetIndex, inspection.detail());
        };
    }

    private void count(PolicyCheckOutcome outcome) {
        Counter.builder(CHECK_METRICS_NAME)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }
}
