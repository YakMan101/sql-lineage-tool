package com.sqllineage.parser;

import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TableType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.BigQuerySqlDialect;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.avatica.util.Quoting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Parser {

    private static final Pattern TYPE_PARAMETER_PATTERN =
        Pattern.compile("(ARRAY|STRUCT)\\s*<[^>]+>", Pattern.CASE_INSENSITIVE);

    public Map<TableEntry, SqlNode> parseCompiledModels(
        List<TableEntry> tables
    ) throws IOException, SqlParseException {
        
        Map<TableEntry, SqlNode> result = new HashMap<>();
        
        for (TableEntry table : tables) {
            if (table.tableType() == TableType.SOURCE || table.tableType() == TableType.SEED) continue;
            result.put(
                table, parseSqlFile(Path.of(table.compiledFilePath()), BigQuerySqlDialect.DEFAULT)
            );
        }
        
        return result;
    }

    public SqlNode parseSqlFile(
        Path compiledSqlPath,
        SqlDialect dialect
    ) throws IOException, SqlParseException {
        String sql = Files.readString(compiledSqlPath);
        
        return parseSql(sql, dialect);
    }

    public SqlNode parseSql(String sql, SqlDialect dialect) throws SqlParseException {
        String preprocessed = preprocess(sql, dialect);
        SqlParser.Config config = SqlParser.config()
            .withQuoting(Quoting.BACK_TICK)
            .withConformance(SqlConformanceEnum.LENIENT);
        
        return SqlParser.create(preprocessed, config).parseQuery();
    }

    private String preprocess(String sql, SqlDialect dialect) {
        if (dialect instanceof BigQuerySqlDialect) {
            sql = stripBigQueryTypeParameters(sql);
        } else {
            throw new UnsupportedOperationException(
                "Unsupported SQL dialect: " + dialect.getClass().getName()
            );
        }

        return sql;
    }

    private String stripBigQueryTypeParameters(String sql) {
        
        return TYPE_PARAMETER_PATTERN.matcher(sql).replaceAll("$1");
    }

}
