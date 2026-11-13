package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

public interface AppliedPolicyInspector {

    // A missing target, a non-concrete/invalid target (e.g. an alias or a malformed name) or an OpenSearch
    // outage throws an IndexWriterException (ordinary error handling); only an unreadable policy on an
    // otherwise valid, existing, concrete target is reported as POLICY_UNDETERMINED
    AppliedPolicyInspection inspect(String targetIndex);
}
