package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

import java.util.Set;
import java.util.TreeSet;

public class JobTargetNotConcreteException extends IndexWriterException {

    public static final String ERROR_CODE = "target-not-concrete";

    private final String targetIndex;
    private final Set<String> resolvedIndices;

    public JobTargetNotConcreteException(String targetIndex, Set<String> resolvedIndices) {
        super("Target '%s' is not a concrete index but resolves to %s; a maintenance job must target one physical index"
                .formatted(targetIndex, new TreeSet<>(resolvedIndices)), false);
        this.targetIndex = targetIndex;
        this.resolvedIndices = new TreeSet<>(resolvedIndices);
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getTargetIndex() {
        return targetIndex;
    }

    public Set<String> getResolvedIndices() {
        return new TreeSet<>(resolvedIndices);
    }
}
