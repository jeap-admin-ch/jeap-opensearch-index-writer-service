package ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.job;

import java.util.Locale;

// Kebab-case form of the job enums as stored in OpenSearch and shown in reports
public final class Wire {

    private Wire() {
    }

    public static String of(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
