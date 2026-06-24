package com.sqllineage.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TableType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests for {@link Ingestion}. */
class IngestionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode parse(String json) throws IOException {
    return MAPPER.readTree(json);
  }

  @Test
  void gatherTables_extractsModel() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "model.project.stg_orders": {
              "resource_type": "model",
              "database": "my-project",
              "schema": "dbt_dev",
              "name": "stg_orders",
              "original_file_path": "models/staging/stg_orders.sql",
              "compiled_path": "target/compiled/project/stg_orders.sql"
            }
          },
          "sources": {}
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(1, tables.size());
    TableEntry entry = tables.get(0);
    assertEquals("my-project.dbt_dev.stg_orders", entry.bqTablePath());
    assertEquals(TableType.MODEL, entry.tableType());
    assertEquals("/dbt/target/compiled/project/stg_orders.sql", entry.compiledFilePath());
  }

  @Test
  void gatherTables_extractsSeed() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "seed.project.orders": {
              "resource_type": "seed",
              "database": "my-project",
              "schema": "dbt_dev",
              "name": "orders",
              "original_file_path": "seeds/orders.csv",
              "compiled_path": null
            }
          },
          "sources": {}
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(1, tables.size());
    TableEntry entry = tables.get(0);
    assertEquals("my-project.dbt_dev.orders", entry.bqTablePath());
    assertEquals(TableType.SEED, entry.tableType());
    assertEquals(null, entry.compiledFilePath());
  }

  @Test
  void gatherTables_extractsSource() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {},
          "sources": {
            "source.project.raw.events": {
              "database": "my-project",
              "schema": "raw",
              "name": "events",
              "original_file_path": "models/sources.yml"
            }
          }
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(1, tables.size());
    TableEntry entry = tables.get(0);
    assertEquals("my-project.raw.events", entry.bqTablePath());
    assertEquals(TableType.SOURCE, entry.tableType());
    assertEquals(null, entry.compiledFilePath());
  }

  @Test
  void gatherTables_mixedTypes_allExtracted() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "model.project.stg_orders": {
              "resource_type": "model",
              "database": "proj",
              "schema": "dev",
              "name": "stg_orders",
              "original_file_path": "models/stg_orders.sql",
              "compiled_path": "target/stg_orders.sql"
            },
            "seed.project.raw_customers": {
              "resource_type": "seed",
              "database": "proj",
              "schema": "dev",
              "name": "raw_customers",
              "original_file_path": "seeds/raw_customers.csv",
              "compiled_path": null
            }
          },
          "sources": {
            "source.project.raw.orders": {
              "database": "proj",
              "schema": "raw",
              "name": "orders",
              "original_file_path": "models/sources.yml"
            }
          }
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(3, tables.size());
    Map<TableType, List<TableEntry>> byType = tables.stream()
        .collect(Collectors.groupingBy(TableEntry::tableType));
    assertEquals(1, byType.get(TableType.MODEL).size());
    assertEquals(1, byType.get(TableType.SEED).size());
    assertEquals(1, byType.get(TableType.SOURCE).size());
  }

  @Test
  void gatherTables_missingSourcesKey_returnsOnlyNodes() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "model.project.stg_orders": {
              "resource_type": "model",
              "database": "proj",
              "schema": "dev",
              "name": "stg_orders",
              "original_file_path": "models/stg_orders.sql",
              "compiled_path": "target/stg_orders.sql"
            }
          }
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(1, tables.size());
    assertEquals(TableType.MODEL, tables.get(0).tableType());
  }

  @Test
  void gatherTables_missingNodesKey_returnsOnlySources() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "sources": {
            "source.project.raw.orders": {
              "database": "proj",
              "schema": "raw",
              "name": "orders",
              "original_file_path": "models/sources.yml"
            }
          }
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertEquals(1, tables.size());
    assertEquals(TableType.SOURCE, tables.get(0).tableType());
  }

  @Test
  void gatherTables_modelMissingCompiledPath_throws() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "model.project.stg_orders": {
              "resource_type": "model",
              "database": "proj",
              "schema": "dev",
              "name": "stg_orders",
              "original_file_path": "models/stg_orders.sql"
            }
          },
          "sources": {}
        }
        """);

    assertThrows(IllegalStateException.class,
        () -> Ingestion.gatherTables(manifest, "/dbt"));
  }

  @Test
  void gatherTables_ignoresUnknownResourceTypes() throws IOException {
    JsonNode manifest = parse(
        """
        {
          "nodes": {
            "test.project.assert_something": {
              "resource_type": "test",
              "database": "proj",
              "schema": "dev",
              "name": "assert_something",
              "original_file_path": "tests/assert_something.sql",
              "compiled_path": "target/assert_something.sql"
            }
          },
          "sources": {}
        }
        """);

    List<TableEntry> tables = Ingestion.gatherTables(manifest, "/dbt");

    assertTrue(tables.isEmpty());
  }
}
