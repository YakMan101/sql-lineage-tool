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

  /** Parses all compiled model SQL files from the given table list using the given dialect. */
  public Map<TableEntry, ParseResult> parseCompiledModels(
      List<TableEntry> tables, Dialect dialect) throws IOException, SqlParseException {
    Map<TableEntry, ParseResult> parsedTables = new HashMap<>();

    for (TableEntry table : tables) {
      if (table.tableType() == TableType.SOURCE || table.tableType() == TableType.SEED) {
        continue;
      }

      parsedTables.put(table, parseSqlFile(Path.of(table.compiledFilePath()), dialect));
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
      case BIGQUERY -> stripBigQueryTypeParameters(sql);
    };
  }

  private String stripBigQueryTypeParameters(String sql) {
    return TYPE_PARAMETER_PATTERN.matcher(sql).replaceAll("$1");
  }
}
