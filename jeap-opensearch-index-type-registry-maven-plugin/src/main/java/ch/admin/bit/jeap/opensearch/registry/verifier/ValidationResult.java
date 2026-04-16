package ch.admin.bit.jeap.opensearch.registry.verifier;

import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Data
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ValidationResult {
    private static final ValidationResult OK = new ValidationResult(true, Collections.emptyList());
    private final boolean valid;
    private final List<String> errors;

    public static ValidationResult ok() {
        return OK;
    }

    public static ValidationResult fail(String error) {
        return new ValidationResult(false, Collections.singletonList(error));
    }

    public static ValidationResult merge(ValidationResult... results) {
        return Arrays.stream(results).reduce(ok(), ValidationResult::merge);
    }

    private static ValidationResult merge(ValidationResult r1, ValidationResult r2) {
        List<String> errors = Stream.concat(r1.getErrors().stream(), r2.getErrors().stream())
                .collect(Collectors.toList());
        return new ValidationResult(r1.isValid() && r2.isValid(), errors);
    }
}
