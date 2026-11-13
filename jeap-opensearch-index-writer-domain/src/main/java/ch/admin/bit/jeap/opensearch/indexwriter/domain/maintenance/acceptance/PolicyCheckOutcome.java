package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

public enum PolicyCheckOutcome {

    ROLLOVER_IN_POLICY(false),
    NO_ROLLOVER_IN_POLICY(true),
    // ISM reports no applied policy right after a policy is attached, so this also covers a policy that is still initializing
    NO_POLICY_APPLIED(true),
    // Also reported while ISM initializes a policy whose id is already known but whose definition is not yet applied
    POLICY_UNDETERMINED(true);

    private final boolean allowed;

    PolicyCheckOutcome(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean allowed() {
        return allowed;
    }
}
