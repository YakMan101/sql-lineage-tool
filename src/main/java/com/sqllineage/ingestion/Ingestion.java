package com.sqllineage.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Ingestion {
    public static void main(String[] args) {
        // TODO
    }

    public static JsonNode readManifestJson(String manifestPath) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode manifest = objectMapper.readTree(new File(manifestPath));
        
        return manifest;
    }

    public static List<String> gatherTables(JsonNode manifest) {
        List<String> tables = new ArrayList<>();
        tables.addAll(getNodes(manifest));
        tables.addAll(getSources(manifest));
        return tables;
    }

    private static List<String> getNodes(JsonNode manifest) {
        List<String> tables = new ArrayList<>();
        JsonNode nodes = manifest.get("nodes");
        if (nodes == null) return tables;

        nodes.forEach(node -> {
            String resourceType = node.path("resource_type").asText();
                if ("model".equals(resourceType) || "seed".equals(resourceType)) {
                    tables.add(toFullyQualifiedTableName(node));
                }
        });

        return tables;
    }

    private static List<String> getSources(JsonNode manifest) {
        List<String> tables = new ArrayList<>();
        JsonNode sources = manifest.get("sources");
        if (sources == null) return tables;

        sources.fields().forEachRemaining(entry -> tables.add(
            toFullyQualifiedTableName(entry.getValue())
        ));
        return tables;
    }

    private static String toFullyQualifiedTableName(JsonNode node) {
        String database = node.path("database").asText();
        String schema = node.path("schema").asText();
        String name = node.path("name").asText();
        return database + "." + schema + "." + name;
    }


    public static void gatherColumnsForTable(String tableName) {
        // TODO
    }

    public static void buildTableColumnMap() {
        // TODO
    }

}
