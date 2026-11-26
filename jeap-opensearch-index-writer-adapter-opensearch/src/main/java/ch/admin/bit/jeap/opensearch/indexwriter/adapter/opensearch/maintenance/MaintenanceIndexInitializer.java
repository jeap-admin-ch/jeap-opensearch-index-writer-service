package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.maintenance;

import ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch.OpenSearchIndexWriterException;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.indexing.writer.IndexTemplateSettings;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.ConditionalOnMaintenanceEnabled;
import ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.MaintenanceProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

// Incompatible indices stop the startup and are never recreated; the instance losing a concurrent creation race verifies the winner's index.
// An index of an older schema version is upgraded in place by adding the missing properties, existing documents are not touched.
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
class MaintenanceIndexInitializer {

    private static final String SETTINGS = "settings";
    private static final String MAPPING_PROPERTIES = "properties";
    private static final String META = "_meta";
    private static final String SCHEMA_VERSION = "maintenance_schema_version";

    private static final String JOB_MAPPING = "maintenance-index-mappings/job-index.json";
    private static final String TASK_MAPPING = "maintenance-index-mappings/task-index.json";

    private final MaintenanceStoreClient client;
    private final MaintenanceProperties properties;
    private final JsonMapper jsonMapper;

    @PostConstruct
    void initialize() {
        ensureIndex(properties.getJobIndex(), JOB_MAPPING);
        ensureIndex(properties.getTaskIndex(), TASK_MAPPING);
    }

    private void ensureIndex(String index, String mappingResource) {
        ObjectNode definition = (ObjectNode) load(mappingResource);
        applyConfiguredSettings(definition);
        MaintenanceStoreClient.Result created = client.execute("PUT", "/" + index, Map.of(), definition);
        if (created.ok()) {
            log.info("Created maintenance index '{}'", index);
            return;
        }
        if (created.status() != 400 || !"resource_already_exists_exception".equals(created.body().path("error").path("type").asString(""))) {
            throw OpenSearchIndexWriterException.maintenanceIndexCreationFailed(index, created.status(), created.body());
        }
        verify(index, definition.path("mappings"));
        log.info("Reusing existing maintenance index '{}'", index);
    }

    private void applyConfiguredSettings(ObjectNode definition) {
        ObjectNode settings = definition.has(SETTINGS) ? (ObjectNode) definition.get(SETTINGS) : definition.putObject(SETTINGS);
        IndexTemplateSettings configured = properties.getIndexSettings();
        if (configured.getNumberOfShards() != null) {
            settings.put("number_of_shards", configured.getNumberOfShards());
        }
        if (configured.getNumberOfReplicas() != null) {
            settings.put("number_of_replicas", configured.getNumberOfReplicas());
        }
        if (configured.getRefreshInterval() != null) {
            settings.put("refresh_interval", configured.getRefreshInterval());
        }
    }

    private void verify(String index, JsonNode expected) {
        JsonNode actual = readMapping(index);
        int expectedVersion = expected.path(META).path(SCHEMA_VERSION).asInt();
        int actualVersion = actual.path(META).path(SCHEMA_VERSION).asInt(-1);
        if (actualVersion < 1) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "maintenance_schema_version is %d, expected %d".formatted(actualVersion, expectedVersion));
        }
        if (!"strict".equals(actual.path("dynamic").asString(""))) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "dynamic mapping is not strict");
        }
        if (actualVersion < expectedVersion) {
            upgrade(index, expected, actual, actualVersion, expectedVersion);
            actual = readMapping(index);
        }
        // A newer schema version (e.g. after a rollback of the service) is accepted if it has every property this version needs
        String mismatch = firstMismatch("", expected.path(MAPPING_PROPERTIES), actual.path(MAPPING_PROPERTIES), false);
        if (mismatch != null) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, mismatch);
        }
    }

    private JsonNode readMapping(String index) {
        MaintenanceStoreClient.Result result = client.execute("GET", "/" + index + "/_mapping", Map.of(), null);
        if (!result.ok() || result.body().isEmpty()) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "mapping could not be read, HTTP %d".formatted(result.status()));
        }
        return result.body().properties().iterator().next().getValue().path("mappings");
    }

    // Conflicting definitions stop the startup before anything is changed; concurrent upgrades by several instances are harmless
    private void upgrade(String index, JsonNode expected, JsonNode actual, int fromVersion, int toVersion) {
        String conflict = firstMismatch("", expected.path(MAPPING_PROPERTIES), actual.path(MAPPING_PROPERTIES), true);
        if (conflict != null) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, conflict);
        }
        ObjectNode body = jsonMapper.createObjectNode();
        body.putObject(META).put(SCHEMA_VERSION, toVersion);
        body.set(MAPPING_PROPERTIES, missingProperties(expected.path(MAPPING_PROPERTIES), actual.path(MAPPING_PROPERTIES)));
        MaintenanceStoreClient.Result result = client.execute("PUT", "/" + index + "/_mapping", Map.of(), body);
        if (!result.ok()) {
            throw OpenSearchIndexWriterException.maintenanceIndexUpgradeFailed(index, fromVersion, toVersion, result.status(), result.body());
        }
        log.info("Upgraded maintenance index '{}' from schema version {} to {}", index, fromVersion, toVersion);
    }

    private ObjectNode missingProperties(JsonNode expected, JsonNode actual) {
        ObjectNode missing = jsonMapper.createObjectNode();
        for (var field : expected.properties()) {
            JsonNode actualField = actual.path(field.getKey());
            if (actualField.isMissingNode()) {
                missing.set(field.getKey(), field.getValue());
            } else if (field.getValue().has(MAPPING_PROPERTIES)) {
                ObjectNode nested = missingProperties(field.getValue().path(MAPPING_PROPERTIES), actualField.path(MAPPING_PROPERTIES));
                if (!nested.isEmpty()) {
                    missing.putObject(field.getKey()).set(MAPPING_PROPERTIES, nested);
                }
            }
        }
        return missing;
    }

    // Additional properties in the existing index are compatible, different definitions are not; missing ones only if allowed
    private static String firstMismatch(String path, JsonNode expected, JsonNode actual, boolean missingAllowed) {
        for (var field : expected.properties()) {
            String name = path + field.getKey();
            JsonNode expectedField = field.getValue();
            JsonNode actualField = actual.path(field.getKey());
            if (actualField.isMissingNode()) {
                if (missingAllowed) {
                    continue;
                }
                return "property '%s' is missing".formatted(name);
            }
            String expectedType = expectedField.path("type").asString("object");
            String actualType = actualField.path("type").asString("object");
            if (!expectedType.equals(actualType)) {
                return "property '%s' has type '%s', expected '%s'".formatted(name, actualType, expectedType);
            }
            if (expectedField.path("enabled").asBoolean(true) != actualField.path("enabled").asBoolean(true)) {
                return "property '%s' differs in 'enabled'".formatted(name);
            }
            String nested = firstMismatch(name + ".", expectedField.path(MAPPING_PROPERTIES), actualField.path(MAPPING_PROPERTIES), missingAllowed);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private JsonNode load(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return jsonMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read resource " + resource, e);
        }
    }
}
