package ch.admin.bit.jeap.opensearch.indextype;

public record MappingVersion<T>(
        int major,
        int minor,
        Class<T> dataClass,
        String mappingDefinition
) {
    public String versionString() {
        return major + "." + minor;
    }
}