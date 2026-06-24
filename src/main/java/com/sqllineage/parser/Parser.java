package com.sqllineage.parser;

import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TableType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/** Parses SQL files into Calcite ASTs for lineage extraction. */
public class Parser {

  /** Supported SQL dialects. */
  public enum Dialect {
    BIGQUERY
  }

  private static final Pattern TYPE_PARAMETER_PATTERN =
      Pattern.compile("(ARRAY|STRUCT)\\s*<[^>]+>", Pattern.CASE_INSENSITIVE);

  // mask type keywords inside CAST(x AS <type>) — they must stay unquoted for Calcite.
  // Uses a placeholder that contains underscores so \b won't fire inside it in step 2.
  private static final Pattern CAST_TYPE_PATTERN =
      Pattern.compile(
          "\\bAS\\s+(TIMESTAMP|DATETIME|DATE|TIME)(?=\\s*\\))",
          Pattern.CASE_INSENSITIVE);

  // quote remaining bare uses of these reserved words (column names, aliases, args).
  // Negative lookbehind on "`" skips already-quoted identifiers; lookahead on "(" skips functions.
  private static final Pattern RESERVED_IDENTIFIER_PATTERN =
      Pattern.compile(
          "(?<!`)\\b(timestamp|datetime|date|time|value|references)\\b(?!\\s*\\()",
          Pattern.CASE_INSENSITIVE);

  // Trailing comma immediately before FROM — valid BigQuery, rejected by Calcite.
  private static final Pattern TRAILING_COMMA_BEFORE_FROM =
      Pattern.compile(",\\s*(\\r?\\n\\s*FROM\\b)", Pattern.CASE_INSENSITIVE);

  // BigQuery SAFE namespace prefix (e.g. safe.parse_date) — strip to just the function name.
  private static final Pattern SAFE_NAMESPACE_PATTERN =
      Pattern.compile("\\bsafe\\.", Pattern.CASE_INSENSITIVE);

  // CAST(x AS DATETIME) — Calcite has no DATETIME type, map to TIMESTAMP.
  private static final Pattern CAST_DATETIME_PATTERN =
      Pattern.compile("\\bAS\\s+DATETIME(?=\\s*\\))", Pattern.CASE_INSENSITIVE);

  // Double-quoted strings like "%d/%m/%Y" — replace with single-quoted equivalents.
  private static final Pattern DOUBLE_QUOTED_STRING_PATTERN =
      Pattern.compile("\"([^\"]*)\"");

  /** Parses all compiled model SQL files from the given table list using the given dialect. */
  public Map<TableEntry, ParseResult> parseCompiledModels(
      List<TableEntry> tables, Dialect dialect) throws IOException {
    Map<TableEntry, ParseResult> parsedTables = new HashMap<>();

    for (TableEntry table : tables) {
      if (table.tableType() == TableType.SOURCE || table.tableType() == TableType.SEED) {
        continue;
      }

      try {
        parsedTables.put(table, parseSqlFile(Path.of(table.compiledFilePath()), dialect));
      } catch (SqlParseException e) {
        System.err.println("Warning: skipping " + table.bqTablePath()
            + " — parse error: " + e.getMessage());
      }
    }

    return parsedTables;
  }

  /** Parses a compiled SQL file using the given dialect. */
  public ParseResult parseSqlFile(Path compiledSqlPath, Dialect dialect)
      throws IOException, SqlParseException {
    String sql = Files.readString(compiledSqlPath);

    return parseSql(sql, dialect);
  }

  /** Parses a SQL string using the given dialect. */
  public ParseResult parseSql(String sql, Dialect dialect) throws SqlParseException {
    String preprocessed = preprocess(sql, dialect);
    SqlNode ast = SqlParser.create(preprocessed, parserConfig(dialect)).parseQuery();

    return new ParseResult(ast, preprocessed);
  }

  private SqlParser.Config parserConfig(Dialect dialect) {
    return switch (dialect) {
      case BIGQUERY -> SqlParser.config()
          .withQuoting(Quoting.BACK_TICK)
          .withConformance(SqlConformanceEnum.LENIENT);
    };
  }

  private String preprocess(String sql, Dialect dialect) {
    return switch (dialect) {
      case BIGQUERY -> preprocessBigQuery(sql);
    };
  }

  private String preprocessBigQuery(String sql) {
    String result = TYPE_PARAMETER_PATTERN.matcher(sql).replaceAll("$1");
    result = TRAILING_COMMA_BEFORE_FROM.matcher(result).replaceAll("$1");
    result = SAFE_NAMESPACE_PATTERN.matcher(result).replaceAll("");
    result = CAST_DATETIME_PATTERN.matcher(result).replaceAll("AS TIMESTAMP");
    result = DOUBLE_QUOTED_STRING_PATTERN.matcher(result).replaceAll("'$1'");
    // Mask CAST type keywords before quoting identifiers, then restore them.
    // "AS __CASTTYPE_n__" survives the identifier pass; we swap it back after.
    result = CAST_TYPE_PATTERN.matcher(result).replaceAll("AS __CASTTYPE_$1__");
    result = RESERVED_IDENTIFIER_PATTERN.matcher(result).replaceAll("`$1`");
    result = result.replaceAll("__CASTTYPE_(\\w+)__", "$1");

    /*
    TODO: BREAKING CASES TO ADDRESS:
    - quotes in quotes e.g: "'"
    - quotes in strings e.g: "i'm buying a house"
    - rank parse error
    - result parse error
    - [] brackets breaking
    */
    return result;
  }
}
