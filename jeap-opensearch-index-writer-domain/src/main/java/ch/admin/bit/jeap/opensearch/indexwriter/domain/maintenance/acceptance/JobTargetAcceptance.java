package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import java.util.Objects;

public record JobTargetAcceptance(String targetIndex, PolicyCheckOutcome outcome, String policyId, String reason) {

    public JobTargetAcceptance {
        Objects.requireNonNull(targetIndex, "targetIndex");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }

    public boolean allowed() {
        return outcome.allowed();
    }

    public JobTargetAcceptance requireAllowed() {
        if (!allowed()) {
            throw new JobTargetNotAllowedException(this);
        }
        return this;
    }
}
