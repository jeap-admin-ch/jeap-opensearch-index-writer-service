package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.Wire;
import ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance.JobDtos.*;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationToken;
import jakarta.servlet.http.HttpServletRequest;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.io.IOException;
import java.io.InputStream;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnMaintenanceEnabled
class JobYaml {

    private final MaintenanceProperties properties;
    private final ObjectMapper yaml;

    JobYaml(MaintenanceProperties properties) {
        this.properties = properties;
        YAMLFactory factory = YAMLFactory.builder()
                .loadSettings(LoadSettings.builder().setCodePointLimit(properties.getMaxRequestBytes()).build())
                .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
                .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
                .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
                .build();
        // Integers (the schema major) must not be silently changed: 1.9 or '1' are rejected. Other scalars stay
        // coercible so that unquoted numeric ids such as `- id: 12345` are read as strings.
        this.yaml = YAMLMapper.builder(factory).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(LogicalType.Integer, c -> c
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    static UUID parseJobId(String jobId) {
        try {
            return UUID.fromString(jobId);
        } catch (IllegalArgumentException _) {
            throw new InvalidJobException("The job id '%s' is not a valid UUID".formatted(jobId));
        }
    }

    JobSubmission readUpsert(UUID jobId, byte[] body, Authentication authentication) {
        UpsertJobRequest dto = read(body, UpsertJobRequest.class);
        if (dto.message() == null || dto.operation() == null) {
            throw new InvalidJobException("Both 'message' and 'operation' must be set");
        }
        if (!IndexOperation.UPSERT.name().equals(dto.operation().indexOperation())) {
            throw new InvalidJobException("operation.indexOperation must be UPSERT");
        }
        List<JobReference> references = references(dto.originReferences(), r -> new JobReference(r.id(), r.version()));
        JobRequest request = new JobRequest(JobType.UPSERT, dto.message().messageName(), dto.message().topicName(),
                dto.operation().indexType(), dto.operation().indexMajorVersion(), references);
        return new JobSubmission(jobId, request, submitter(authentication));
    }

    JobSubmission readDelete(UUID jobId, byte[] body, Authentication authentication) {
        DeleteJobRequest dto = read(body, DeleteJobRequest.class);
        List<JobReference> references = references(dto.originReferences(), r -> new JobReference(r.id(), null));
        JobRequest request = new JobRequest(JobType.DELETE, null, null, dto.indexType(), dto.indexMajorVersion(), references);
        return new JobSubmission(jobId, request, submitter(authentication));
    }

    String write(Object document) {
        return yaml.writeValueAsString(document);
    }

    JobDtos.JobReport toDto(JobModel.JobReport report) {
        Job job = report.job();
        JobRequest request = job.request();
        boolean upsert = request.type() == JobType.UPSERT;
        List<ReportReference> references = new ArrayList<>(report.entries().size());
        for (ReportEntry entry : report.entries()) {
            references.add(new ReportReference(entry.taskId().toString(), entry.originId(), Wire.of(entry.state()), entry.version(),
                    entry.actualIndexMajorVersion(), entry.actualIndexMinorVersion(), entry.attempts(), error(entry.error()),
                    entry.targets() == null ? null : entry.targets().stream().map(JobYaml::targetOutcome).toList()));
        }
        // The input sections echo the submitted selector (absent if omitted); the resolved majors are reported in the targets
        return new JobDtos.JobReport(
                upsert ? new Message(request.messageName(), request.topicName()) : null,
                upsert ? new ReportOperation(request.indexType(), IndexOperation.UPSERT.name(), request.indexMajorVersion()) : null,
                upsert ? null : request.indexType(),
                upsert ? null : request.indexMajorVersion(),
                job.jobId().toString(), Wire.of(request.type()), Wire.of(job.state()),
                job.startedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                report.reportCreated().truncatedTo(ChronoUnit.SECONDS).toString(),
                job.submittedBy().name(), job.submittedBy().extId(),
                new JobDtos.ReportSummary(report.summary().totalReferences(), report.summary().materializedTasks(),
                        report.summary().open(), report.summary().succeeded(), report.summary().failed(),
                        report.summary().indexingDisabled()),
                references, new ReportPage(report.limit(), references.size(), report.nextCursor()),
                upsert ? target(job.target()) : null,
                upsert ? null : Wire.of(job.selection()),
                upsert ? null : job.targets().stream()
                        .map(t -> new ReportJobTarget(t.indexMajorVersion(), t.physicalIndex(), t.rolloverDetection())).toList(),
                dispatch(job),
                Wire.of(job.result()),
                job.completedAt() == null ? null : job.completedAt().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    private static ReportTarget target(JobTarget target) {
        return new ReportTarget(Wire.of(target.selection()), target.indexMajorVersion(), target.physicalIndex(), target.rolloverDetection());
    }

    private static ReportTargetOutcome targetOutcome(TargetOutcome outcome) {
        return new ReportTargetOutcome(outcome.indexMajorVersion(), outcome.targetIndex(), Wire.of(outcome.outcome()), error(outcome.error()));
    }

    private static ReportError error(TaskError error) {
        return error == null ? null : new ReportError(error.code(), error.message(), error.targetIndex(), error.traceId());
    }

    private static ReportDispatch dispatch(Job job) {
        Dispatch dispatch = job.dispatch();
        if (dispatch.redispatches() == 0 && dispatch.stuckAt() == null) {
            return null;
        }
        boolean stuck = dispatch.stuckAt() != null && job.state() == JobState.OPEN;
        return new ReportDispatch(dispatch.redispatches(),
                stuck ? dispatch.stuckAt().truncatedTo(ChronoUnit.SECONDS).toString() : null);
    }

    // Reads at most max-request-bytes + 1 bytes, so an oversized body is never buffered as a whole
    byte[] readBody(HttpServletRequest request) {
        int max = properties.getMaxRequestBytes();
        if (request.getContentLengthLong() > max) {
            throw tooLarge("is %d bytes".formatted(request.getContentLengthLong()));
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(max == Integer.MAX_VALUE ? max : max + 1);
        } catch (IOException e) {
            throw new InvalidJobException("The request body could not be read: " + e.getMessage());
        }
        if (body.length > max) {
            throw tooLarge("exceeds %d bytes".formatted(max));
        }
        return body;
    }

    private InvalidJobException tooLarge(String size) {
        return new InvalidJobException("The request %s, the maximum is %d; the request is rejected as a whole"
                .formatted(size, properties.getMaxRequestBytes()));
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            T value = yaml.readValue(body, type);
            if (value == null) {
                throw new InvalidJobException("The request body is empty");
            }
            return value;
        } catch (JacksonException e) {
            throw new InvalidJobException("Invalid YAML request: " + e.getOriginalMessage());
        }
    }

    private static <D> List<JobReference> references(List<D> dtos, java.util.function.Function<D, JobReference> mapper) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream().map(d -> {
            if (d == null) {
                throw new InvalidJobException("originReferences must not contain empty entries");
            }
            return mapper.apply(d);
        }).toList();
    }

    private static SubmittedBy submitter(Authentication authentication) {
        if (authentication instanceof JeapAuthenticationToken token) {
            return new SubmittedBy(token.getTokenName(), token.getTokenExtId());
        }
        return new SubmittedBy(authentication == null ? null : authentication.getName(), null);
    }
}
