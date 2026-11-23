package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.exception.IndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.*;
import ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance.JobDtos.ErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {UpsertJobController.class, DeleteJobController.class})
@ConditionalOnMaintenanceEnabled
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
class JobApiExceptionHandler {

    private final JobYaml yaml;

    @ExceptionHandler(InvalidJobException.class)
    ResponseEntity<String> invalid(InvalidJobException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e);
    }

    @ExceptionHandler(JobNotFoundException.class)
    ResponseEntity<String> notFound(JobNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", e);
    }

    @ExceptionHandler(JobConflictException.class)
    ResponseEntity<String> conflict(JobConflictException e) {
        return error(HttpStatus.CONFLICT, "JOB_CONFLICT", e);
    }

    @ExceptionHandler({JobTargetNotAllowedException.class, JobTargetLayoutNotSupportedException.class})
    ResponseEntity<String> notAllowed(IndexWriterException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ALLOWED", e);
    }

    @ExceptionHandler({JobTargetNotFoundException.class, JobTargetNameInvalidException.class, JobTargetNotConcreteException.class,
            JobTargetNotWritableException.class})
    ResponseEntity<String> targetInvalid(IndexWriterException e) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "TARGET_INVALID", e);
    }

    @ExceptionHandler({JobStorageUnavailableException.class, JobTargetPolicyCheckFailedException.class})
    ResponseEntity<String> unavailable(IndexWriterException e) {
        log.warn("Job storage or target check unavailable", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                "The job storage or the target check is temporarily unavailable; retry later");
    }

    @ExceptionHandler(JobStorageRejectedException.class)
    ResponseEntity<String> rejected(JobStorageRejectedException e) {
        log.error("Job storage rejected a document", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_REJECTED", "The job storage rejected the job data");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid value for parameter '%s'".formatted(e.getName()));
    }

    // Keeps the YAML error format for unexpected failures; security and Spring's own status exceptions are rethrown
    // unchanged so that their regular handling (401, 403, ...) applies
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<String> unexpected(RuntimeException e) {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException || e instanceof org.springframework.web.ErrorResponse) {
            throw e;
        }
        log.error("Unexpected error in the job API", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error, see the service log");
    }

    private ResponseEntity<String> error(HttpStatus status, String code, Exception e) {
        return error(status, code, e.getMessage());
    }

    // Storage failures are answered with a fixed message, their details (index names, OpenSearch bodies) are only logged
    private ResponseEntity<String> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JobApiMediaTypes.YAML).body(yaml.write(new ErrorResponse(code, message)));
    }
}
