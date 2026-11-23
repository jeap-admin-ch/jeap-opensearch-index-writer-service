package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "jeap.opensearch.indexwriter.maintenance", name = "enabled", havingValue = "true")
public @interface ConditionalOnMaintenanceEnabled {
}
