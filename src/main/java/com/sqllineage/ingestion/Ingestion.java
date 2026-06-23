package com.sqllineage.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TableType;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads dbt manifest.json and gathers table metadata for lineage extraction. */
public class Ingestion {

  /** Reads and parses a dbt manifest.json file. */
  public static JsonNode readManifestJson(String manifestPath) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    return objectMapper.readTree(new File(manifestPath));
  }

  /** Returns all model, seed, and source table entries from the manifest. */
  public static List<TableEntry> gatherTables(JsonNode manifest, String dbtProjectRoot) {
    List<TableEntry> tables = new ArrayList<>();
    tables.addAll(getNodes(manifest, dbtProjectRoot));
    tables.addAll(getSources(manifest));
    return tables;
  }

  private static List<TableEntry> getNodes(JsonNode manifest, String dbtProjectRoot) {
    List<TableEntry> tables = new ArrayList<>();
    JsonNode nodes = manifest.get("nodes");
    if (nodes == null) {
      return tables;
    }
    nodes.forEach(node -> {
      String resourceType = node.path("resource_type").asText();
      if ("model".equals(resourceType) || "seed".equals(resourceType)) {
        boolean isSeed = "seed".equals(resourceType);
        TableType tableType = isSeed ? TableType.SEED : TableType.MODEL;
        tables.add(new TableEntry(
            toFullyQualifiedTableName(node),
            node.path("original_file_path").asText(),
            isSeed ? null : dbtProjectRoot + "/" + getCompiledPath(node),
            tableType
        ));
      }
    });
    return tables;
  }

  private static List<TableEntry> getSources(JsonNode manifest) {
    List<TableEntry> tables = new ArrayList<>();
    JsonNode sources = manifest.get("sources");
    if (sources == null) {
      return tables;
    }
    sources.forEach(source -> tables.add(new TableEntry(
        toFullyQualifiedTableName(source),
        source.path("original_file_path").asText(),
        null,
        TableType.SOURCE
    )));
    return tables;
  }

  private static String getCompiledPath(JsonNode node) {
    JsonNode compiledPath = node.path("compiled_path");
    if (compiledPath.isNull() || compiledPath.isMissingNode()) {
      throw new IllegalStateException(
          "Compiled path missing for node '" + node.path("name").asText() + "'. "
              + "Run 'dbt compile' to generate compiled SQL files before running this tool.");
    }
    return compiledPath.asText();
  }

  private static String toFullyQualifiedTableName(JsonNode node) {
    String database = node.path("database").asText();
    String schema = node.path("schema").asText();
    String name = node.path("name").asText();
    return database + "." + schema + "." + name;
  }
}
