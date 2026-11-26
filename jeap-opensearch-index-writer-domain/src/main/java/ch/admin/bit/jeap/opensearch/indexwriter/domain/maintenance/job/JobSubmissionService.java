package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptance;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptanceCheck;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.JobDispatcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.InvalidJobException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobConflictException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobExceptions.JobStorageRejectedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.JobTargetResolver;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobStorage.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
public class JobSubmissionService {

    // Stored as keywords on the tasks; far below the Lucene term limit of 32766 bytes even for 4-byte characters
    static final int MAX_REFERENCE_LENGTH = 512;

    private final MaintenanceProperties properties;
    private final IndexTypeRepository indexTypeRepository;
    private final MessageConfigurationRepository messageConfigurationRepository;
    private final JobTargetResolver targetResolver;
    private final JobTargetAcceptanceCheck acceptanceCheck;
    private final JobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final JobClock clock;
    private final MaterializationExecutor materializationExecutor;
    private final JobReferenceCache referenceCache;
    private final JobDispatcher dispatcher;
    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    public Job submit(JobSubmission submission) {
        JobRequest request = submission.request();
        validateStructure(request);
        String requestHash = requestHash(request);

        // An equivalent retry of a stored job is answered even if the configuration or the limits changed since
        Optional<Job> existing = jobRepository.find(submission.jobId(), false);
        if (existing.isPresent()) {
            return resumeExisting(existing.get(), requestHash);
        }

        validateAgainstConfiguration(request);
        IndexType<?> indexType = resolveIndexType(request);
        JobTarget target = resolveTarget(request, indexType);
        Job job = new Job(submission.jobId(), requestHash, JobState.OPEN, null, request.references().size(),
                MaterializationState.MATERIALIZING, clock.now(), null, submission.submittedBy(), request, target, Dispatch.NONE, 0, 0);
        if (!jobRepository.create(job)) {
            Job concurrent = jobRepository.find(submission.jobId(), false).orElseThrow(() -> new JobConflictException(submission.jobId()));
            return resumeExisting(concurrent, requestHash);
        }
        log.info("Stored {} job {} with {} references for target {}", Wire.of(request.type()), job.jobId(),
                job.expectedTaskCount(), target.physicalIndex());
        Job stored = jobRepository.find(job.jobId(), false).orElseThrow(() -> new JobConflictException(job.jobId()));
        // The report answering this request needs the references just stored, in the same order
        referenceCache.put(stored, request.references());
        // Creating up to max-references tasks can outlast HTTP timeouts, so the request is answered right away. Only the
        // job id is queued, the references are read when the task creation starts instead of being held in the queue.
        materializeInBackground(job.jobId());
        return stored;
    }

    // Safe to run repeatedly and concurrently: existing tasks, including their results, are never touched
    public Job materialize(Job job) {
        if (job.materializationState() != MaterializationState.MATERIALIZING || !inProgress.add(job.jobId())) {
            return job;
        }
        try {
            return materializeTasks(job);
        } finally {
            inProgress.remove(job.jobId());
        }
    }

    private Job materializeTasks(Job job) {
        if (job.materializationState() != MaterializationState.MATERIALIZING) {
            return job;
        }
        List<JobReference> references = job.request().references();
        // A job read without its references would otherwise be marked materialized without any task
        if (references.size() != job.expectedTaskCount()) {
            throw new IllegalStateException("Job %s has %d of its %d references loaded, task creation needs all of them"
                    .formatted(job.jobId(), references.size(), job.expectedTaskCount()));
        }
        int batchSize = properties.getMaterializationBatchSize();
        if (batchSize < 1) {
            throw new IllegalStateException("materialization-batch-size must be positive, is " + batchSize);
        }
        Instant createdAt = clock.now();
        // Every step can be rejected permanently (e.g. a missing permission), not only the task creation itself
        try {
            for (int from = 0; from < references.size(); from += batchSize) {
                int to = Math.min(from + batchSize, references.size());
                List<JobTask> batch = new ArrayList<>(to - from);
                for (int position = from; position < to; position++) {
                    JobReference reference = references.get(position);
                    batch.add(JobTask.open(job.jobId(), position, reference.id(), reference.version(), createdAt));
                }
                taskRepository.createMissing(batch);
            }
            taskRepository.refresh();
            jobRepository.markMaterialized(job);
        } catch (JobStorageRejectedException e) {
            return failMaterialization(job, e);
        }
        Job materialized = jobRepository.find(job.jobId(), false).orElse(job);
        dispatch(materialized);
        return materialized;
    }

    // Started right away so that a job does not wait for the next housekeeping run, which also resumes a failed dispatch
    private void dispatch(Job job) {
        try {
            dispatcher.dispatch(job);
        } catch (RuntimeException e) {
            log.warn("Sending the commands of job {} failed, the housekeeping resumes it", job.jobId(), e);
        }
    }

    // Retrying a rejected batch can never succeed, the job is ended so that it is visible as failed and cleaned up
    private Job failMaterialization(Job job, JobStorageRejectedException cause) {
        log.error("Task creation of job {} was rejected by the storage, the job is completed as failed", job.jobId(), cause);
        jobRepository.failMaterialization(job, clock.now());
        return jobRepository.find(job.jobId(), false).orElse(job);
    }

    private Job resumeExisting(Job existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new JobConflictException(existing.jobId());
        }
        if (existing.materializationState() != MaterializationState.MATERIALIZING) {
            return existing;
        }
        // The stored order of the references defines the task positions, it may differ from the retried request
        materializeInBackground(existing.jobId());
        return existing;
    }

    // A retried PUT of a job whose tasks are still queued or being created on this instance adds no further run
    private void materializeInBackground(UUID jobId) {
        if (!inProgress.add(jobId)) {
            return;
        }
        boolean accepted = materializationExecutor.submit(() -> {
            try {
                jobRepository.find(jobId, true).ifPresent(this::materializeTasks);
            } catch (RuntimeException e) {
                log.warn("Task creation of job {} failed, the housekeeping resumes it", jobId, e);
            } finally {
                inProgress.remove(jobId);
            }
        });
        if (!accepted) {
            inProgress.remove(jobId);
        }
    }

    private static void validateStructure(JobRequest request) {
        if (request.indexType() == null || request.indexType().isBlank()) {
            throw new InvalidJobException("indexType must be set");
        }
        if (request.indexMajorVersion() != null && request.indexMajorVersion() < 1) {
            throw new InvalidJobException("indexMajorVersion must be a positive integer");
        }
        List<JobReference> references = request.references();
        if (references == null || references.isEmpty()) {
            throw new InvalidJobException("originReferences must contain at least one reference");
        }
        Set<String> ids = HashSet.newHashSet(references.size());
        for (JobReference reference : references) {
            validateReference(reference, request.type());
            if (!ids.add(reference.id())) {
                throw new InvalidJobException("Duplicate origin reference id '%s'; the version does not make an id distinct".formatted(reference.id()));
            }
        }
    }

    private static void validateReference(JobReference reference, JobType type) {
        if (reference == null || reference.id() == null || reference.id().isBlank()) {
            throw new InvalidJobException("Every origin reference needs a non-blank id");
        }
        if (reference.id().length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidJobException("Origin reference id '%s…' is longer than %d characters"
                    .formatted(reference.id().substring(0, 40), MAX_REFERENCE_LENGTH));
        }
        if (reference.version() != null && reference.version().length() > MAX_REFERENCE_LENGTH) {
            throw new InvalidJobException("The version of origin reference '%s' is longer than %d characters"
                    .formatted(reference.id(), MAX_REFERENCE_LENGTH));
        }
        if (containsControlCharacter(reference.id()) || containsControlCharacter(reference.version())) {
            throw new InvalidJobException("Origin reference '%s' contains a control character in its id or version"
                    .formatted(reference.id().codePoints().filter(c -> !Character.isISOControl(c))
                            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)));
        }
        if (type == JobType.DELETE && reference.version() != null) {
            throw new InvalidJobException("Delete references must not have a version (id '%s')".formatted(reference.id()));
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }

    private void validateAgainstConfiguration(JobRequest request) {
        if (request.references().size() > properties.getMaxReferences()) {
            throw new InvalidJobException("originReferences contains %d references, the maximum is %d; the request is rejected as a whole"
                    .formatted(request.references().size(), properties.getMaxReferences()));
        }
        if (request.type() == JobType.UPSERT) {
            validateUpsertOperation(request);
        }
    }

    private void validateUpsertOperation(JobRequest request) {
        MessageConfig message = messageConfigurationRepository.findByName(request.messageName())
                .orElseThrow(() -> new InvalidJobException("Unknown message '%s'".formatted(request.messageName())));
        if (!Objects.equals(message.topicName(), request.topicName())) {
            throw new InvalidJobException("Message '%s' is not configured for topic '%s'".formatted(request.messageName(), request.topicName()));
        }
        boolean operationConfigured = message.operations().stream()
                .anyMatch(op -> op.indexOperation() == IndexOperation.UPSERT && op.indexType().equals(request.indexType()));
        if (!operationConfigured) {
            throw new InvalidJobException("Message '%s' has no UPSERT operation for index type '%s'".formatted(request.messageName(), request.indexType()));
        }
    }

    private IndexType<?> resolveIndexType(JobRequest request) {
        if (request.indexMajorVersion() != null) {
            return indexTypeRepository.findByOriginTypeAndMajorVersion(request.indexType(), request.indexMajorVersion())
                    .orElseThrow(() -> new InvalidJobException("Index type '%s' has no configured schema major %d"
                            .formatted(request.indexType(), request.indexMajorVersion())));
        }
        return indexTypeRepository.findHighestMajorVersion(request.indexType())
                .orElseThrow(() -> new InvalidJobException("Unknown index type '%s'".formatted(request.indexType())));
    }

    private JobTarget resolveTarget(JobRequest request, IndexType<?> indexType) {
        ResolvedTarget resolved = targetResolver.resolveWriteTarget(indexType.indexWriteAlias());
        JobTargetAcceptance acceptance = acceptanceCheck.check(resolved.physicalIndex()).requireAllowed();
        String rollover = acceptance.outcome() == PolicyCheckOutcome.POLICY_UNDETERMINED
                ? JobTarget.ROLLOVER_UNDETERMINED : JobTarget.ROLLOVER_NOT_DETECTED;
        MajorSelection selection = request.indexMajorVersion() != null ? MajorSelection.EXPLICIT : MajorSelection.HIGHEST;
        return new JobTarget(selection, indexType.majorVersion(), resolved.physicalIndex(), resolved.physicalIndexUuid(), rollover);
    }

    // Submitter data and timestamps are deliberately not part of the hash, the selector and reference versions are
    static String requestHash(JobRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(Wire.of(request.type())).append('\n')
                .append(request.messageName()).append('\n')
                .append(request.topicName()).append('\n')
                .append(request.indexType()).append('\n')
                .append(request.indexMajorVersion() == null ? "omitted" : request.indexMajorVersion().toString()).append('\n');
        request.references().stream()
                .sorted(Comparator.comparing(JobReference::id))
                .forEach(r -> canonical.append(r.id().length()).append(':').append(r.id()).append('|')
                        .append(r.version() == null ? "-" : r.version().length() + ":" + r.version()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
