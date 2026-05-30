package com.sqllineage.cli;

import java.io.IOException;

import com.sqllineage.ingestion.Ingestion;

public class Main {
    public static void main(String[] args) throws IOException {
        Ingestion.readManifestJson("dbt_project/target/manifest.json");
    }
}
