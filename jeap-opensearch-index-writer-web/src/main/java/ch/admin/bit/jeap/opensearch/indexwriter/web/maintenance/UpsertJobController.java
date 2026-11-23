package ch.admin.bit.jeap.opensearch.indexwriter.web.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobReport;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobSubmission;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobReportService;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobSubmissionService;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@ConditionalOnMaintenanceEnabled
// "OIDC" is the security scheme the jEAP swagger starter registers when an authorization server is configured
@SecurityRequirement(name = "OIDC")
@RequestMapping("/api/upsert-jobs")
@Tag(name = "Upsert jobs", description = "Maintenance jobs that re-index a list of origin references")
@RequiredArgsConstructor
@Slf4j
class UpsertJobController {

    private static final String UPSERT_EXAMPLE = """
            message:
              messageName: JmeDecreeDocumentCreatedEvent
              topicName: jme-decree-document-created
            operation:
              indexType: DecreeDocument
              indexOperation: UPSERT
              indexMajorVersion: 1
            originReferences:
            - id: '12345'
              version: '17'
            - id: '67890'
            """;
    private static final String REPORT_EXAMPLE = """
            message:
              messageName: JmeDecreeDocumentCreatedEvent
              topicName: jme-decree-document-created
            operation:
              indexType: DecreeDocument
              indexOperation: UPSERT
              indexMajorVersion: 1
            job-id: 88dbb65f-9634-4685-bc86-17b72d715d3e
            job-type: upsert
            job-state: open
            started: '2026-10-23T09:00:00Z'
            report-created: '2026-10-23T09:00:05Z'
            started-by-name: Example Operator
            started-by-ext-id: '123456'
            summary:
              total-references: 2
              materialized-tasks: 2
              open: 2
              succeeded: 0
              failed: 0
            originReferences:
            - task-id: 1780f333-a55c-44a9-8732-6ed061c97d57
              id: '12345'
              state: open
              version: '17'
            - task-id: 5b0f8e4c-1d2a-4c61-9b7e-3f7a2d9c4e10
              id: '67890'
              state: open
            page:
              limit: 100
              returned: 2
              next-cursor: null
            target:
              selection: explicit
              index-major-version: 1
              physical-index: jme_decree_document_v1-000001
              rollover: not-detected
            """;

    private final JobSubmissionService submissionService;
    private final JobReportService reportService;
    private final JobYaml yaml;

    @PutMapping(value = "/{jobId}", consumes = {"application/yaml", "application/x-yaml"}, produces = "application/yaml")
    @PreAuthorize("hasRole('upsertjob','create')")
    @Operation(summary = "Submit an upsert job", description = """
            Stores the job and answers right away; one task per origin reference is created in the background
            (materialized-tasks in the report). The job selects exactly one schema major with
            operation.indexMajorVersion, the highest configured major if omitted. The reference version is stored and
            reported only, it never selects a schema. Submitting the same job id with equivalent content returns the
            stored job. Required role: upsertjob#create""",
            requestBody = @RequestBody(required = true, content = {
                    @Content(mediaType = "application/yaml", schema = @Schema(implementation = JobDtos.UpsertJobRequest.class),
                            examples = @ExampleObject(name = "Upsert job", value = UPSERT_EXAMPLE)),
                    @Content(mediaType = "application/x-yaml", schema = @Schema(implementation = JobDtos.UpsertJobRequest.class),
                            examples = @ExampleObject(name = "Upsert job", value = UPSERT_EXAMPLE))}),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The job report", content = @Content(mediaType = "application/yaml",
                            schema = @Schema(implementation = JobDtos.JobReport.class), examples = @ExampleObject(value = REPORT_EXAMPLE))),
                    @ApiResponse(responseCode = "400", description = "Invalid input, duplicate ids, unknown schema major or exceeded limits"),
                    @ApiResponse(responseCode = "401", description = "Not authenticated"),
                    @ApiResponse(responseCode = "403", description = "Role upsertjob#create missing"),
                    @ApiResponse(responseCode = "409", description = "The job id exists with different content"),
                    @ApiResponse(responseCode = "415", description = "Content type is not application/yaml or application/x-yaml"),
                    @ApiResponse(responseCode = "422", description = "NOT_ALLOWED: the target is not allowed for maintenance jobs (rollover policy, multi-index layout); TARGET_INVALID: the write alias of the selected major does not exist or is not a concrete index. Nothing is stored"),
                    @ApiResponse(responseCode = "503", description = "The job storage is unavailable")})
    ResponseEntity<String> submit(@PathVariable String jobId, HttpServletRequest request, Authentication authentication) {
        log.info("Submitting upsert job {} with content type {}", jobId, request.getContentType());
        UUID id = JobYaml.parseJobId(jobId);
        JobSubmission submission = yaml.readUpsert(id, yaml.readBody(request), authentication);
        Job job = submissionService.submit(submission);
        return report(job.jobId(), null, null);
    }

    @GetMapping(value = "/{jobId}", produces = "application/yaml")
    @PreAuthorize("hasRole('jobreport','read')")
    @Operation(summary = "Get an upsert job report", description = """
            Reports the submitted input, the resolved schema major and target, summary counts and one page of
            references covering every id, including ids whose task is not yet created. Required role: jobreport#read""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The job report", content = @Content(mediaType = "application/yaml",
                            schema = @Schema(implementation = JobDtos.JobReport.class), examples = @ExampleObject(value = REPORT_EXAMPLE))),
                    @ApiResponse(responseCode = "400", description = "Invalid job id, limit or cursor"),
                    @ApiResponse(responseCode = "401", description = "Not authenticated"),
                    @ApiResponse(responseCode = "403", description = "Role jobreport#read missing"),
                    @ApiResponse(responseCode = "404", description = "Unknown job"),
                    @ApiResponse(responseCode = "503", description = "The job storage is unavailable")})
    ResponseEntity<String> get(@PathVariable String jobId,
                               @Parameter(description = "Page size") @RequestParam(required = false) Integer limit,
                               @Parameter(description = "next-cursor of the previous page") @RequestParam(required = false) String cursor) {
        log.info("Reporting upsert job {} with limit {} and cursor {}", jobId, limit, cursor);
        return report(JobYaml.parseJobId(jobId), cursor, limit);
    }

    private ResponseEntity<String> report(UUID jobId, String cursor, Integer limit) {
        JobReport report = reportService.report(jobId, JobType.UPSERT, cursor, limit);
        return ResponseEntity.ok().contentType(JobApiMediaTypes.YAML).body(yaml.write(yaml.toDto(report)));
    }
}
