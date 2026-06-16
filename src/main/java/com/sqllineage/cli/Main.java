package com.sqllineage.cli;

import java.io.IOException;

import com.sqllineage.ingestion.Ingestion;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException {
        JsonNode manifest = Ingestion.readManifestJson(
            "dbt_project/target/manifest.json"
        );
        List<String> tables = Ingestion.gatherTables(manifest);

        System.out.println("Tables found in the manifest:");
        for (String table : tables) {
            System.out.println(table);
        }
    }
}
