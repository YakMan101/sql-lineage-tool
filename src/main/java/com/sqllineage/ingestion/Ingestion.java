package com.sqllineage.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;

public class Ingestion {
    public static void main(String[] args) {
        // TODO
    }

    public static void gatherTables() {
        // TODO
    }

    public static void readManifestJson(String manifestPath) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode manifest = objectMapper.readTree(new File(manifestPath));
        System.out.println(manifest.toPrettyString());
    }

    public static void gatherColumnsForTable(String tableName) {
        // TODO
    }

    public static void buildTableColumnMap() {
        // TODO
    }

}
