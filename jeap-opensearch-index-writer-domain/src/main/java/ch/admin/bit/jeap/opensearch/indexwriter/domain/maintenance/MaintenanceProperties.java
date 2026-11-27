package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@ConfigurationProperties(prefix = "jeap.opensearch.indexwriter.maintenance")
public class MaintenanceProperties {

    /**
     * Enables the maintenance job API, the initialisation of the job and task indices and the housekeeping.
     */
    private boolean enabled = false;

    /**
     * Maximum number of origin references in one job.
     */
    @Positive
    private int maxReferences = 100_000;

    /**
     * Maximum size of a job request in bytes. The stored job holds all references, so this must stay below the
     * HTTP payload limit of the OpenSearch cluster (http.max_content_length, 10 MiB on small AWS instance types).
     */
    @Positive
    private int maxRequestBytes = 8 * 1024 * 1024;

    /**
     * Number of tasks created per storage request.
     */
    // A task takes up to ~4.5 KB (id and version of 512 characters, up to 4 bytes each in JSON, plus fields), so 2000
    // tasks stay below the 10 MiB HTTP payload limit of small AWS instance types. This relies on control characters
    // being rejected at submission: JSON escapes them to 6 bytes each.
    @Positive
    @Max(2_000)
    private int materializationBatchSize = 1000;

    /**
     * Finished jobs and their tasks are deleted this long after completion.
     */
    private Duration retention = Duration.ofDays(30);

    @Positive
    private int defaultPageSize = 100;

    // Pages are read with a single search, which OpenSearch limits to index.max_result_window (default 10000) hits
    @Positive
    @Max(10_000)
    private int maxPageSize = 1000;

    /**
     * Shards, replicas and refresh interval of the job and task indices. Applied when the indices are created only,
     * existing indices keep their settings.
     */
    @NestedConfigurationProperty
    private IndexTemplateSettings indexSettings = new IndexTemplateSettings(1, 1, "1s");

    /**
     * Name of the OpenSearch index holding the jobs.
     */
    @NotBlank
    private String jobIndex = "jeap_indexwriter_jobs";

    /**
     * Name of the OpenSearch index holding the job tasks.
     */
    @NotBlank
    private String taskIndex = "jeap_indexwriter_tasks";

    /**
     * Interval of the housekeeping (resume of interrupted task creation, job completion, retention).
     */
    private Duration housekeepingInterval = Duration.ofMinutes(1);

    /**
     * A job whose task creation has not finished this long after its start is resumed by the housekeeping. Must be
     * longer than the task creation of the largest job takes, otherwise the housekeeping creates the same tasks again
     * in parallel (harmless, but wasted work).
     */
    private Duration materializationResumeAfter = Duration.ofMinutes(10);

    /**
     * Topic of the UpsertSearchItemCommand the service sends to itself, one per task. Must be specific to the service
     * instance: a topic shared with another system's index writer would deliver its commands to this one.
     */
    @NotBlank
    private String upsertCommandTopic;

    /**
     * Number of maintenance tasks run in parallel per instance.
     */
    @Positive
    private int commandConsumerConcurrency = 1;

    /**
     * Number of task commands sent per batch. The dispatch progress is stored after each batch, a crash sends at most
     * one batch twice.
     */
    @Positive
    @Max(10_000)
    private int dispatchBatchSize = 500;

    /**
     * While an instance sends the commands of a job, the other instances leave it alone for this long. Renewed after
     * every batch.
     */
    private Duration dispatchLease = Duration.ofMinutes(2);

    /**
     * Attempts of a task that fails transiently (provider or OpenSearch unavailable), the first try included. After
     * the last attempt the task fails.
     */
    @Positive
    private int taskMaxAttempts = 6;

    /**
     * Delay before the first retry of a task; it doubles with every further attempt up to task-retry-max-backoff.
     */
    private Duration taskRetryInitialBackoff = Duration.ofMinutes(1);

    private Duration taskRetryMaxBackoff = Duration.ofMinutes(30);

    /**
     * The commands of unfinished tasks are sent again if a fully dispatched job made no progress for this long, e.g.
     * because a command was lost in a crash before its result was saved. This is not counted as an attempt. Must be
     * longer than the time the command topic needs to drain the largest backlog, otherwise commands that are merely
     * queued behind other jobs are sent again.
     */
    private Duration taskRedispatchAfter = Duration.ofHours(2);

    /**
     * Rounds of sending the unfinished tasks of a stalled job again. A job that stalls once more after the last round
     * is reported as stuck: nothing is sent again, its open tasks still finish if their commands arrive.
     */
    @PositiveOrZero
    private int taskMaxRedispatches = 3;

    @AssertTrue(message = "default-page-size must not exceed max-page-size")
    public boolean isDefaultPageSizeWithinMaxPageSize() {
        return defaultPageSize <= maxPageSize;
    }

    @AssertTrue(message = "retention, housekeeping-interval, materialization-resume-after, dispatch-lease, task-retry-initial-backoff, task-retry-max-backoff and task-redispatch-after must be positive")
    public boolean isDurationsPositive() {
        return isPositive(retention) && isPositive(housekeepingInterval) && isPositive(materializationResumeAfter)
               && isPositive(dispatchLease) && isPositive(taskRetryInitialBackoff) && isPositive(taskRetryMaxBackoff)
               && isPositive(taskRedispatchAfter);
    }

    @AssertTrue(message = "task-retry-initial-backoff must not exceed task-retry-max-backoff")
    public boolean isRetryBackoffOrdered() {
        return taskRetryInitialBackoff == null || taskRetryMaxBackoff == null || taskRetryInitialBackoff.compareTo(taskRetryMaxBackoff) <= 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && duration.isPositive();
    }
}
