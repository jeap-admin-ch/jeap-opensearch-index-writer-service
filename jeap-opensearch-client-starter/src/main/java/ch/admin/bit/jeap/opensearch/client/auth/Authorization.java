package ch.admin.bit.jeap.opensearch.client.auth;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;

public record Authorization(Set<String> userroles, Map<String, Set<String>> bproles) {

    public Authorization {
        Objects.requireNonNull(userroles, "userroles must not be null");
        Objects.requireNonNull(bproles, "bproles must not be null");
        userroles = Set.copyOf(userroles);
        bproles = bproles.entrySet().stream()
                .collect(toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }

    public Set<String> allRoles() {
        Set<String> all = new HashSet<>(userroles);
        bproles.values().forEach(all::addAll);
        return Collections.unmodifiableSet(all);
    }

    public Set<String> getAllBusinessPartnerIdsWithAnyOf(Set<String> roles) {
        Objects.requireNonNull(roles, "roles must not be null");
        return bproles.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(roles::contains))
                .map(Map.Entry::getKey)
                .collect(toUnmodifiableSet());
    }

    public boolean hasUserroleAnyOf(Set<String> roles) {
        Objects.requireNonNull(roles, "roles must not be null");
        return roles.stream().anyMatch(userroles::contains);
    }
}
