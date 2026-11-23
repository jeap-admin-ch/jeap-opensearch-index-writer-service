package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;

import java.util.UUID;

public final class JobExceptions {

    private JobExceptions() {
    }

    public static class InvalidJobException extends IndexWriterException {
        public InvalidJobException(String message) {
            super(message, false);
        }
    }

    public static class JobNotFoundException extends IndexWriterException {
        public JobNotFoundException(UUID jobId) {
            super("Job '%s' not found".formatted(jobId), false);
        }
    }

    public static class JobConflictException extends IndexWriterException {
        public JobConflictException(UUID jobId) {
            super("Job '%s' already exists with different content; use a new job id for a different request".formatted(jobId), false);
        }
    }

    public static class JobStorageUnavailableException extends IndexWriterException {
        public JobStorageUnavailableException(String message, Throwable cause) {
            super(message, true, cause);
        }
    }

    public static class JobStorageRejectedException extends IndexWriterException {
        public JobStorageRejectedException(String message) {
            super(message, false);
        }
    }

    public static class JobTasksNotDeletedException extends IndexWriterException {
        public JobTasksNotDeletedException(String message) {
            super(message, true);
        }
    }

    public static class JobTargetNotWritableException extends IndexWriterException {
        public JobTargetNotWritableException(String writeAlias, String index) {
            super("Write alias '%s' points to index '%s' with is_write_index=false; fix the alias before submitting maintenance jobs"
                    .formatted(writeAlias, index), false);
        }
    }

    public static class JobTargetLayoutNotSupportedException extends IndexWriterException {
        public JobTargetLayoutNotSupportedException(String writeAlias, int indexCount) {
            super("Write alias '%s' points to %d indices; only a single concrete target index is supported for maintenance jobs"
                    .formatted(writeAlias, indexCount), false);
        }
    }
}
