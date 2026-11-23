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

// Incompatible indices stop the startup and are never recreated; the instance losing a concurrent creation race verifies the winner's index
@Component
@ConditionalOnMaintenanceEnabled
@RequiredArgsConstructor
@Slf4j
class MaintenanceIndexInitializer {

    private static final String SETTINGS = "settings";
    private static final String MAPPING_PROPERTIES = "properties";

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
        MaintenanceStoreClient.Result result = client.execute("GET", "/" + index + "/_mapping", Map.of(), null);
        if (!result.ok() || result.body().isEmpty()) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "mapping could not be read, HTTP %d".formatted(result.status()));
        }
        JsonNode actual = result.body().properties().iterator().next().getValue().path("mappings");
        int expectedVersion = expected.path("_meta").path("maintenance_schema_version").asInt();
        int actualVersion = actual.path("_meta").path("maintenance_schema_version").asInt(-1);
        if (actualVersion != expectedVersion) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "maintenance_schema_version is %d, expected %d".formatted(actualVersion, expectedVersion));
        }
        if (!"strict".equals(actual.path("dynamic").asString(""))) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, "dynamic mapping is not strict");
        }
        String mismatch = firstMismatch("", expected.path(MAPPING_PROPERTIES), actual.path(MAPPING_PROPERTIES));
        if (mismatch != null) {
            throw OpenSearchIndexWriterException.incompatibleMaintenanceIndex(index, mismatch);
        }
    }

    // Additional properties in the existing index are compatible, missing ones or different definitions are not
    private static String firstMismatch(String path, JsonNode expected, JsonNode actual) {
        for (var field : expected.properties()) {
            String name = path + field.getKey();
            JsonNode expectedField = field.getValue();
            JsonNode actualField = actual.path(field.getKey());
            if (actualField.isMissingNode()) {
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
            String nested = firstMismatch(name + ".", expectedField.path(MAPPING_PROPERTIES), actualField.path(MAPPING_PROPERTIES));
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
