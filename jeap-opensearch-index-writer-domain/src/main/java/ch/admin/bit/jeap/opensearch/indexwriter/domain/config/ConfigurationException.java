package ch.admin.bit.jeap.opensearch.indexwriter.domain.config;

import java.util.List;

public class ConfigurationException extends RuntimeException {

    private ConfigurationException(String message) {
        super(message);
    }

    public static ConfigurationException noMessageConfigurationFound(String location) {
        return new ConfigurationException("No index writer message configuration found on classpath at " + location);
    }

    public static ConfigurationException noIndexTypeFound() {
        return new ConfigurationException("No IndexType implementations found on classpath.");
    }

    public static ConfigurationException indexingConditionNotFound(String condition) {
        return new ConfigurationException("Indexing condition bean " + condition + " could not be found");
    }

    public static ConfigurationException referenceProviderNotFound(String referenceProviderName) {
        return new ConfigurationException("Reference provider bean " + referenceProviderName + " could not be found");
    }

    public static ConfigurationException referenceProviderNotConfigured(String messageName) {
        return new ConfigurationException("Reference provider for " + messageName + " is not configured");
    }

    public static ConfigurationException requiredOperationFieldsMissing(String messageName, List<String> fieldNames) {
        return new ConfigurationException("Required field(s) " + fieldNames + " missing in operation configuration for message '" + messageName + "'");
    }
}
