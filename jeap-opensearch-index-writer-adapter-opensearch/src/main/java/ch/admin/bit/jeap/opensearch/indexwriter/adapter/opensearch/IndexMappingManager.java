package ch.admin.bit.jeap.opensearch.indexwriter.adapter.opensearch;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.AliasDefinition;
import org.opensearch.client.opensearch.indices.GetAliasRequest;
import org.opensearch.client.opensearch.indices.GetAliasResponse;
import org.opensearch.client.opensearch.indices.GetMappingRequest;
import org.opensearch.client.opensearch.indices.GetMappingResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;
import org.opensearch.client.opensearch.indices.PutMappingRequest;
import org.opensearch.client.opensearch.indices.get_mapping.IndexMappingRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
@Component
@RequiredArgsConstructor
@Slf4j
class IndexMappingManager {

    static final String SCHEMA_VERSION_META_KEY = "schema_version";
    private static final String MAPPINGS_JSON_KEY = "mappings";
    private static final String META_JSON_KEY = "_meta";
    private static final String ISM_ROLLOVER_ALIAS_SETTING = "plugins.index_state_management.rollover_alias";

    private final OpenSearchClient openSearchClient;
    private final DataFieldValidator dataFieldValidator;

    TypeMapping parseMappingWithVersion(String indexWriteAlias, InputStream inputStream, int minorVersion) throws IOException {
        @SuppressWarnings("resource")
        JsonpMapper jsonpMapper = openSearchClient._transport().jsonpMapper();
        byte[] bytes = inputStream.readAllBytes();
        if (bytes.length == 0) {
            throw OpenSearchIndexWriterException.emptyMapping(indexWriteAlias);
        }

        // Use the default Jakarta JSON provider (parsson) for reading/writing raw JSON,
        // as the JacksonJsonProvider from the mapper does not support createReader/createWriter.
        try (var reader = Json.createReader(new ByteArrayInputStream(bytes))) {
            JsonObject root = reader.readObject();
            JsonObject mappings = root.getJsonObject(MAPPINGS_JSON_KEY);
            dataFieldValidator.cacheMapping(indexWriteAlias, mappings);

            JsonObjectBuilder metaBuilder = Json.createObjectBuilder();
            if (mappings.containsKey(META_JSON_KEY)) {
                mappings.getJsonObject(META_JSON_KEY).forEach((k, v) -> {
                    if (!k.equals(SCHEMA_VERSION_META_KEY)) {
                        metaBuilder.add(k, v);
                    }
                });
            }
            metaBuilder.add(SCHEMA_VERSION_META_KEY, String.valueOf(minorVersion));

            JsonObject mappingsWithMeta = Json.createObjectBuilder(mappings)
                    .add(META_JSON_KEY, metaBuilder.build())
                    .build();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (var writer = Json.createWriter(baos)) {
                writer.writeObject(mappingsWithMeta);
            }

            try (var parser = Json.createParser(new ByteArrayInputStream(baos.toByteArray()))) {
                return TypeMapping._DESERIALIZER.deserialize(parser, jsonpMapper);
            }
        }
    }

    void ensureMappingUpToDate(String indexWriteAlias, int minorVersion, TypeMapping typeMapping) throws IOException {
        // Specify index="*" so this is resolved as an index-level permission (indices:admin/aliases/get)
        // rather than a cluster-level operation. Without an index, GET /_alias/{name} requires cluster scope.
        GetAliasRequest getAliasRequest = new GetAliasRequest.Builder()
                .index("*")
                .name(indexWriteAlias)
                .build();
        GetAliasResponse aliasResponse;
        try {
            aliasResponse = openSearchClient.indices().getAlias(getAliasRequest);
        } catch (OpenSearchException e) {
            if (e.status() == 404) {
                throw OpenSearchIndexWriterException.writeAliasNotFound(indexWriteAlias);
            }
            throw e;
        }
        String physicalWriteIndex = resolvePhysicalWriteIndex(aliasResponse, indexWriteAlias);
        try {
            ensureRolloverAlias(physicalWriteIndex, indexWriteAlias);
            if (isMappingUpToDate(physicalWriteIndex, minorVersion)) {
                log.debug("Mapping for index '{}' is already up-to-date at version {}", physicalWriteIndex, minorVersion);
            } else {
                log.info("Updating mapping for index '{}' to version {}", physicalWriteIndex, minorVersion);
                updateMapping(physicalWriteIndex, typeMapping);
            }
        } catch (OpenSearchException e) {
            if ("cluster_block_exception".equals(e.response().error().type())) {
                logClusterBlockWarning(physicalWriteIndex, indexWriteAlias, e.response().error().reason());
            } else {
                throw e;
            }
        } catch (IOException e) {
            // The HTTP transport (ApacheHttpClient5Transport) can surface cluster_block_exception as
            // ResponseException (a subtype of IOException) rather than OpenSearchException.
            if (e.getMessage() != null && e.getMessage().contains("cluster_block_exception")) {
                logClusterBlockWarning(physicalWriteIndex, indexWriteAlias, e.getMessage());
            } else {
                throw e;
            }
        }
    }

    private void logClusterBlockWarning(String physicalIndex, String writeAlias, String detail) {
        log.warn("Skipping mapping update for index '{}' (alias '{}') — index is blocked: {}. " +
                 "The mapping will be updated on the next startup once the block is removed.",
                physicalIndex, writeAlias, detail);
    }

    private String resolvePhysicalWriteIndex(GetAliasResponse aliasResponse, String indexWriteAlias) {
        return aliasResponse.result().entrySet().stream()
                .filter(e -> {
                    AliasDefinition def = e.getValue().aliases().get(indexWriteAlias);
                    return def != null && Boolean.TRUE.equals(def.isWriteIndex());
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> {
                    Set<String> allIndices = aliasResponse.result().keySet();
                    if (allIndices.size() != 1) {
                        throw OpenSearchIndexWriterException.ambiguousWriteIndex(indexWriteAlias, allIndices);
                    }
                    return allIndices.iterator().next();
                });
    }

    private void ensureRolloverAlias(String physicalIndex, String writeAlias) throws IOException {
        PutIndicesSettingsRequest request = new PutIndicesSettingsRequest.Builder()
                .index(physicalIndex)
                .settings(IndexSettings.of(s -> s
                        .customSettings(ISM_ROLLOVER_ALIAS_SETTING, JsonData.of(writeAlias))))
                .build();
        openSearchClient.indices().putSettings(request);
        log.debug("Set ISM rollover alias '{}' on index '{}'", writeAlias, physicalIndex);
    }

    private boolean isMappingUpToDate(String indexName, int minorVersion) throws IOException {
        GetMappingRequest getMappingRequest = new GetMappingRequest.Builder()
                .index(indexName)
                .build();
        GetMappingResponse response = openSearchClient.indices().getMapping(getMappingRequest);
        IndexMappingRecord record = response.result().get(indexName);
        if (record == null) {
            return false;
        }
        Map<String, JsonData> meta = record.mappings().meta();
        if (meta.isEmpty()) {
            return false;
        }
        JsonData versionData = meta.get(SCHEMA_VERSION_META_KEY);
        if (versionData == null) {
            return false;
        }
        @SuppressWarnings("resource")
        JsonpMapper mapper = openSearchClient._transport().jsonpMapper();
        String currentVersion = versionData.to(String.class, mapper);
        return String.valueOf(minorVersion).equals(currentVersion);
    }

    private void updateMapping(String indexName, TypeMapping typeMapping) throws IOException {
        PutMappingRequest.Builder putMappingBuilder = new PutMappingRequest.Builder()
                .index(indexName);
        if (!typeMapping.meta().isEmpty()) {
            putMappingBuilder.meta(typeMapping.meta());
        }
        if (!typeMapping.properties().isEmpty()) {
            putMappingBuilder.properties(typeMapping.properties());
        }
        if (typeMapping.dynamic() != null) {
            putMappingBuilder.dynamic(typeMapping.dynamic());
        }
        openSearchClient.indices().putMapping(putMappingBuilder.build());
    }
}
