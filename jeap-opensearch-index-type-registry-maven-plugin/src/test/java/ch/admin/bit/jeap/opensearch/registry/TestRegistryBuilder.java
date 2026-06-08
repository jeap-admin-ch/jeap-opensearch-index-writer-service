package ch.admin.bit.jeap.opensearch.registry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Helper that builds minimal valid index type registry directory structures in a temp dir.
 */
public class TestRegistryBuilder {

    public static final String VALID_DESCRIPTOR = """
            {
              "system": "JME",
              "originType": "JmeDecreeDocument",
              "description": "Test index type.",
              "roles": ["jme_read"],
              "mappingVersions": [
                {
                  "major": 1,
                  "minor": 0,
                  "mappingDefinition": "JmeDecreeDocument_mapping_v1_0.json"
                }
              ]
            }
            """;

    public static final String VALID_MAPPING_V1_0 = """
            {
              "mappings": {
                "dynamic": false,
                "properties": {
                  "search_item": {
                    "type": "object",
                    "properties": {
                      "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "major_version": { "type": "integer" },
                      "minor_version": { "type": "integer" }
                    }
                  },
                  "origin": {
                    "type": "object",
                    "properties": {
                      "id":       { "type": "keyword" },
                      "version":  { "type": "keyword" },
                      "bp_id":     { "type": "keyword" },
                      "created":  { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "reference": { "type": "object", "enabled": false }
                    }
                  },
                  "data": {
                    "type": "object",
                    "properties": {
                      "document_id": { "type": "keyword" },
                      "decree_reference": {
                        "type": "object",
                        "properties": {
                          "type": { "type": "keyword" },
                          "id":   { "type": "keyword" }
                        }
                      },
                      "created_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" }
                    }
                  }
                }
              }
            }
            """;

    public static final String VALID_MAPPING_V1_1_ADDS_FIELD = """
            {
              "mappings": {
                "dynamic": false,
                "properties": {
                  "search_item": {
                    "type": "object",
                    "properties": {
                      "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "major_version": { "type": "integer" },
                      "minor_version": { "type": "integer" }
                    }
                  },
                  "origin": {
                    "type": "object",
                    "properties": {
                      "id":       { "type": "keyword" },
                      "version":  { "type": "keyword" },
                      "bp_id":     { "type": "keyword" },
                      "created":  { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "reference": { "type": "object", "enabled": false }
                    }
                  },
                  "data": {
                    "type": "object",
                    "properties": {
                      "document_id": { "type": "keyword" },
                      "decree_reference": {
                        "type": "object",
                        "properties": {
                          "type": { "type": "keyword" },
                          "id":   { "type": "keyword" }
                        }
                      },
                      "created_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "status":     { "type": "keyword" }
                    }
                  }
                }
              }
            }
            """;

    public static final String VALID_MAPPING_V1_1_REMOVES_FIELD = """
            {
              "mappings": {
                "dynamic": false,
                "properties": {
                  "search_item": {
                    "type": "object",
                    "properties": {
                      "upserted_at":   { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "major_version": { "type": "integer" },
                      "minor_version": { "type": "integer" }
                    }
                  },
                  "origin": {
                    "type": "object",
                    "properties": {
                      "id":       { "type": "keyword" },
                      "version":  { "type": "keyword" },
                      "bp_id":     { "type": "keyword" },
                      "created":  { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "modified": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                      "reference": { "type": "object", "enabled": false }
                    }
                  },
                  "data": {
                    "type": "object",
                    "properties": {
                      "decree_reference": {
                        "type": "object",
                        "properties": {
                          "type": { "type": "keyword" },
                          "id":   { "type": "keyword" }
                        }
                      },
                      "created_at": { "type": "date", "format": "strict_date_optional_time||epoch_millis" }
                    }
                  }
                }
              }
            }
            """;

    private final File rootDir;

    public TestRegistryBuilder(File rootDir) {
        this.rootDir = rootDir;
    }

    /** Creates the full valid index type: jme/jmedecreedocument/JmeDecreeDocument.json + mapping. */
    public File buildValidIndexType() throws IOException {
        return buildIndexType("jme", "jmedecreedocument", "JmeDecreeDocument",
                VALID_DESCRIPTOR, VALID_MAPPING_V1_0);
    }

    /** Creates an index type directory with the given descriptor and single mapping. */
    public File buildIndexType(String system, String dirName, String typeName,
                                String descriptor, String mapping) throws IOException {
        File indexTypeDir = mkdirs(rootDir, system, dirName);
        write(indexTypeDir, typeName + ".json", descriptor);
        write(indexTypeDir, typeName + "_mapping_v1_0.json", mapping);
        return indexTypeDir;
    }

    /** Writes a file with the given content. */
    public static void write(File dir, String filename, String content) throws IOException {
        Files.writeString(new File(dir, filename).toPath(), content);
    }

    /** Creates nested directories and returns the deepest one. */
    public static File mkdirs(File base, String... parts) throws IOException {
        File dir = base;
        for (String part : parts) {
            dir = new File(dir, part);
        }
        Files.createDirectories(dir.toPath());
        return dir;
    }
}
