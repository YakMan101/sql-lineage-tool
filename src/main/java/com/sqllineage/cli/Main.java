package com.sqllineage.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqllineage.ingestion.Ingestion;
import com.sqllineage.model.TableEntry;
import com.sqllineage.parser.Parser;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException, SqlParseException {
        JsonNode manifest = Ingestion.readManifestJson(
            "dbt_project/target/manifest.json"
        );
        List<TableEntry> tables = Ingestion.gatherTables(manifest, "dbt_project");

        System.out.println("Tables found in manifest:");
        for (TableEntry table : tables) {
            System.out.println("  " + table.bqTablePath() + " (" + table.tableType() + ")");
        }

        System.out.println("\nParsing stg_products.sql:");
        Parser parser = new Parser();

        for (TableEntry table : tables) {
            if (table.tableType() != com.sqllineage.model.TableType.MODEL) continue;
            System.out.println("  " + table.bqTablePath());
            SqlNode ast = parser.parseSqlFile(
                Path.of(table.compiledFilePath()),
                org.apache.calcite.sql.dialect.BigQuerySqlDialect.DEFAULT
            );
            System.out.println(ast.toString());
            System.out.println(" --------------------------------- ");
        }
    }
}
