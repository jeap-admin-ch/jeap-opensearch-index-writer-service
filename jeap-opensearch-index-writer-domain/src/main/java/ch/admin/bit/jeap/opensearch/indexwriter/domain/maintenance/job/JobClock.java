package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import java.time.Clock;
import java.time.Instant;

// A dedicated type instead of a Clock bean, which would clash with a Clock bean of the consuming service
public final class JobClock {

    private final Clock clock;

    public JobClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
