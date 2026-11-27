package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import ch.admin.bit.jeap.opensearch.indextype.IndexType;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.config.indextype.IndexTypeRepository;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptance;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetAcceptanceCheck;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.JobTargetNotAllowedException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance.PolicyCheckOutcome;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution.JobDispatcher;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Which majors a job targets: a delete without indexMajorVersion covers every configured major, all others exactly one
class JobSubmissionTargetsTest {

    private final InMemoryJobStorage storage = new InMemoryJobStorage();
    private final IndexTypeRepository indexTypes = mock(IndexTypeRepository.class);
    private final JobTargetAcceptanceCheck acceptanceCheck = mock(JobTargetAcceptanceCheck.class);
    private final IndexType<?> v1 = indexType(1);
    private final IndexType<?> v2 = indexType(2);
    private JobSubmissionService service;

    @BeforeEach
    void setUp() {
        doReturn(List.of(v1, v2)).when(indexTypes).findAllMajorVersions("DecreeDocument");
        doReturn(Optional.of(v1)).when(indexTypes).findByOriginTypeAndMajorVersion("DecreeDocument", 1);
        doReturn(Optional.of(v2)).when(indexTypes).findHighestMajorVersion("DecreeDocument");
        when(acceptanceCheck.check(anyString())).thenAnswer(call -> new JobTargetAcceptance(call.getArgument(0),
                PolicyCheckOutcome.NO_POLICY_APPLIED, null, "no policy"));
        JobStorage.JobTargetResolver resolver = alias -> new ResolvedTarget(alias.replace("_write", "-000001"), "uuid-" + alias);
        service = new JobSubmissionService(new MaintenanceProperties(), indexTypes, null, resolver, acceptanceCheck, storage, storage,
                new JobClock(Clock.systemUTC()), InMemoryJobStorage.RUN_DIRECTLY, new JobReferenceCache(), mock(JobDispatcher.class));
    }

    @Test
    void deleteWithoutMajor_targetsEveryConfiguredMajor() {
        Job job = service.submit(submission(deleteRequest(null)));

        assertThat(job.selection()).isEqualTo(MajorSelection.ALL);
        assertThat(job.targets()).extracting(JobTarget::indexMajorVersion, JobTarget::physicalIndex)
                .containsExactly(tuple(1, "decree_document_v1-000001"),
                        tuple(2, "decree_document_v2-000001"));
    }

    @Test
    void deleteWithoutMajor_isRejectedAsAWholeIfOneTargetIsRefused() {
        when(acceptanceCheck.check("decree_document_v2-000001")).thenReturn(new JobTargetAcceptance("decree_document_v2-000001",
                PolicyCheckOutcome.ROLLOVER_IN_POLICY, "rolling", "rollover action in policy"));
        JobSubmission submission = submission(deleteRequest(null));

        assertThatThrownBy(() -> service.submit(submission))
                .isInstanceOfSatisfying(JobTargetNotAllowedException.class,
                        e -> assertThat(e.getTargetIndex()).isEqualTo("decree_document_v2-000001"));
        assertThat(storage.jobs).as("nothing is stored").isEmpty();
    }

    @Test
    void deleteWithMajor_targetsOnlyThatMajor() {
        Job job = service.submit(submission(deleteRequest(1)));

        assertThat(job.selection()).isEqualTo(MajorSelection.EXPLICIT);
        assertThat(job.targets()).extracting(JobTarget::indexMajorVersion).containsExactly(1);
    }

    @Test
    void upsertWithoutMajor_stillTargetsTheHighestMajor() {
        JobRequest upsert = new JobRequest(JobType.UPSERT, "Msg", "topic", "DecreeDocument", null, List.of(new JobReference("a", null)));
        JobSubmissionService upsertService = serviceWithUpsertConfiguration();

        Job job = upsertService.submit(submission(upsert));

        assertThat(job.selection()).isEqualTo(MajorSelection.HIGHEST);
        assertThat(job.target().indexMajorVersion()).isEqualTo(2);
    }

    private JobSubmissionService serviceWithUpsertConfiguration() {
        var messages = mock(ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfigurationRepository.class);
        var operation = new ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageOperationConfig("DecreeDocument",
                ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.IndexOperation.UPSERT, "http://provider", null, null, null, null);
        when(messages.findByName("Msg")).thenReturn(Optional.of(
                new ch.admin.bit.jeap.opensearch.indexwriter.domain.config.message.MessageConfig("Msg", "topic", List.of(operation))));
        JobStorage.JobTargetResolver resolver = alias -> new ResolvedTarget(alias.replace("_write", "-000001"), "uuid");
        return new JobSubmissionService(new MaintenanceProperties(), indexTypes, messages, resolver, acceptanceCheck, storage, storage,
                new JobClock(Clock.systemUTC()), InMemoryJobStorage.RUN_DIRECTLY, new JobReferenceCache(), mock(JobDispatcher.class));
    }

    private static JobRequest deleteRequest(Integer major) {
        return new JobRequest(JobType.DELETE, null, null, "DecreeDocument", major, List.of(new JobReference("12345", null)));
    }

    private static JobSubmission submission(JobRequest request) {
        return new JobSubmission(UUID.randomUUID(), request, new SubmittedBy("tester", null));
    }

    private static IndexType<?> indexType(int major) {
        IndexType<?> indexType = mock(IndexType.class);
        when(indexType.indexWriteAlias()).thenReturn("decree_document_v%d_write".formatted(major));
        when(indexType.majorVersion()).thenReturn(major);
        return indexType;
    }
}
