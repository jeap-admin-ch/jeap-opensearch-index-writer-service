package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenancePropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(VALIDATOR.validate(valid())).isEmpty();
    }

    @Test
    void upsertCommandTopicIsRequired() {
        MaintenanceProperties properties = valid();
        properties.setUpsertCommandTopic(" ");

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("upsertCommandTopic");
    }

    @Test
    void retryBackoffMustBeOrdered() {
        MaintenanceProperties properties = valid();
        properties.setTaskRetryInitialBackoff(Duration.ofHours(1));

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("retryBackoffOrdered");
    }

    @Test
    void nonPositiveMaxAttemptsIsInvalid() {
        MaintenanceProperties properties = valid();
        properties.setTaskMaxAttempts(0);

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("taskMaxAttempts");
    }

    @Test
    void nonPositiveBatchSizeIsInvalid() {
        MaintenanceProperties properties = valid();
        properties.setMaterializationBatchSize(0);

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("materializationBatchSize");
    }

    @Test
    void pageSizeLimitsAreChecked() {
        MaintenanceProperties tooLarge = valid();
        tooLarge.setMaxPageSize(10_001);
        MaintenanceProperties defaultAboveMax = valid();
        defaultAboveMax.setDefaultPageSize(2000);

        assertThat(paths(VALIDATOR.validate(tooLarge))).containsExactly("maxPageSize");
        assertThat(paths(VALIDATOR.validate(defaultAboveMax))).containsExactly("defaultPageSizeWithinMaxPageSize");
    }

    @Test
    void nonPositiveDurationIsInvalid() {
        MaintenanceProperties properties = valid();
        properties.setRetention(Duration.ZERO);

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("durationsPositive");
    }

    // Everything has a default except the topic, which is specific to each service instance
    private static MaintenanceProperties valid() {
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setUpsertCommandTopic("my-upsert-search-item-command");
        return properties;
    }

    private static Set<String> paths(Set<ConstraintViolation<MaintenanceProperties>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }
}
