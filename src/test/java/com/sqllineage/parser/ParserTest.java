// TODO: should probs seperate out parser tests and SQL Dialect specific tests in different files

package com.sqllineage.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link Parser} preprocessing and parsing. */
class ParserTest {

  private final Parser parser = new Parser();

  private ParseResult parse(String sql) {
    return assertDoesNotThrow(() -> parser.parseSql(sql, Parser.Dialect.BIGQUERY));
  }

  @Test
  void parse_simpleSelect() {
    ParseResult result = parse("SELECT id FROM `project.dataset.table`");
    assertNotNull(result.ast());
  }

  @Test
  void parse_arrayTypeParameter_stripped() {
    // ARRAY<STRING> as a column alias survives preprocessing as just ARRAY with type param removed
    // We test via a column that references the preprocessed form Calcite can handle
    ParseResult result = parse(
        """
        SELECT col AS col
        FROM `project.dataset.table`
        WHERE STRUCT<x INT64> IS NOT NULL
        """);
    assertTrue(result.preprocessedSql().contains("STRUCT")
        && !result.preprocessedSql().contains("<x INT64>"));
  }

  @Test
  void parse_trailingCommaBeforeFrom_removed() {
    // BigQuery allows a trailing comma after the last SELECT item; Calcite does not
    ParseResult result = parse(
        """
        SELECT
            id,
            name,
        FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_safeNamespacePrefix_stripped() {
    // SAFE.PARSE_DATE is a BigQuery construct — preprocessor strips the "safe." prefix
    ParseResult result = parse(
        """
        SELECT safe.parse_date('%d/%m/%Y', col) AS col
        FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_castDatetime_replacedWithTimestamp() {
    // Calcite has no DATETIME type — preprocessor maps it to TIMESTAMP
    ParseResult result = parse(
        """
        SELECT CAST(col AS DATETIME) AS col
        FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_doubleQuotedString_replacedWithSingleQuoted() {
    // BigQuery allows double-quoted strings in function args; Calcite expects single quotes
    ParseResult result = parse(
        """
        SELECT FORMAT_DATE("%Y-%m-%d", col) AS col
        FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_timestampAsColumnName_quoted() {
    // "timestamp" is reserved in Calcite but valid as a column name in BigQuery
    ParseResult result = parse(
        """
        SELECT id, timestamp FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_timestampAsColumnArg_quoted() {
    // timestamp as argument to the timestamp() function
    ParseResult result = parse(
        """
        SELECT timestamp(timestamp) AS timestamp FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_castTimestampInput_quoted() {
    // "timestamp" as the input to CAST, with the type keyword preserved unquoted
    ParseResult result = parse(
        """
        SELECT CAST(timestamp AS TIMESTAMP) AS timestamp FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_valueAsColumnName_quoted() {
    // "value" is reserved in Calcite but valid as a column name in BigQuery
    ParseResult result = parse(
        """
        SELECT id, value FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_referencesAsColumnName_quoted() {
    // "references" is reserved in Calcite but valid as a column name in BigQuery
    ParseResult result = parse(
        """
        SELECT id, references FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }

  @Test
  void parse_alreadyBacktickQuoted_notDoubleQuoted() {
    // Identifiers already wrapped in backticks must not get a second layer of backticks
    ParseResult result = parse(
        """
        SELECT `table`.`value`, `table`.`timestamp`
        FROM `project.dataset.table`
        """);
    assertNotNull(result.ast());
  }
}
