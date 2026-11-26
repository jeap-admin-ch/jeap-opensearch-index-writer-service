package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.execution;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.InMemoryJobStorage;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobClock;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Dispatch;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.Job;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobModel.JobTarget;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.JobState;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MajorSelection;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job.MaterializationState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TargetIndexGuardTest {

    private static final String INDEX = "index_v1-000001";

    private final MaintenanceTargetWriter writer = mock(MaintenanceTargetWriter.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-26T12:00:00Z"));
    private final TargetIndexGuard guard = new TargetIndexGuard(writer, new JobClock(clock));
    private final Job job = InMemoryJobStorage.job(UUID.randomUUID(), InMemoryJobStorage.upsert("a"), JobState.OPEN,
            MaterializationState.MATERIALIZED, clock.instant(), null);

    @Test
    void requireTarget_failsIfTheIndexNoLongerExists() {
        when(writer.findIndexUuid(INDEX)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireTarget(job))
                .isInstanceOfSatisfying(TargetWriteException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                        .isEqualTo(TaskErrorCode.TARGET_INDEX_MISSING))
                .hasMessageContaining("no longer exists");
    }

    @Test
    void requireTarget_failsIfTheIndexWasRecreatedUnderTheSameName() {
        when(writer.findIndexUuid(INDEX)).thenReturn(Optional.of("new-uuid"));

        assertThatThrownBy(() -> guard.requireTarget(job))
                .isInstanceOfSatisfying(TargetWriteException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getCode())
                        .isEqualTo(TaskErrorCode.TARGET_INDEX_MISSING))
                .hasMessageContaining("recreated");
    }

    @Test
    void requireTarget_reusesASuccessfulCheckForAMinute() {
        when(writer.findIndexUuid(INDEX)).thenReturn(Optional.of("uuid"));

        guard.requireTarget(job);
        clock.advance(Duration.ofSeconds(59));
        guard.requireTarget(job);
        verify(writer, times(1)).findIndexUuid(INDEX);

        clock.advance(Duration.ofSeconds(2));
        guard.requireTarget(job);
        verify(writer, times(2)).findIndexUuid(INDEX);
    }

    @Test
    void requireTarget_onlyChecksTheExistenceForJobsWithoutAKnownUuid() {
        Job withoutUuid = new Job(job.jobId(), job.requestHash(), job.state(), null, 1, job.materializationState(), job.startedAt(),
                null, job.submittedBy(), job.request(),
                new JobTarget(MajorSelection.EXPLICIT, 1, INDEX, null, JobTarget.ROLLOVER_NOT_DETECTED), Dispatch.NONE, 0, 1);
        when(writer.findIndexUuid(INDEX)).thenReturn(Optional.of("any"));

        assertThatCode(() -> guard.requireTarget(withoutUuid)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
