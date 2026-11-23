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
        assertThat(VALIDATOR.validate(new MaintenanceProperties())).isEmpty();
    }

    @Test
    void nonPositiveBatchSizeIsInvalid() {
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setMaterializationBatchSize(0);

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("materializationBatchSize");
    }

    @Test
    void pageSizeLimitsAreChecked() {
        MaintenanceProperties tooLarge = new MaintenanceProperties();
        tooLarge.setMaxPageSize(10_001);
        MaintenanceProperties defaultAboveMax = new MaintenanceProperties();
        defaultAboveMax.setDefaultPageSize(2000);

        assertThat(paths(VALIDATOR.validate(tooLarge))).containsExactly("maxPageSize");
        assertThat(paths(VALIDATOR.validate(defaultAboveMax))).containsExactly("defaultPageSizeWithinMaxPageSize");
    }

    @Test
    void nonPositiveDurationIsInvalid() {
        MaintenanceProperties properties = new MaintenanceProperties();
        properties.setRetention(Duration.ZERO);

        assertThat(paths(VALIDATOR.validate(properties))).containsExactly("durationsPositive");
    }

    private static Set<String> paths(Set<ConstraintViolation<MaintenanceProperties>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }
}
