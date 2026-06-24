package com.sqllineage.parser;

import org.apache.calcite.sql.SqlNode;

/**
 * The result of parsing a SQL string: the AST and the preprocessed SQL text used to produce it.
 * The preprocessed SQL is kept alongside the AST so callers can slice exact snippets using
 * the {@link org.apache.calcite.sql.parser.SqlParserPos} positions carried by each node.
 *
 * <p>TODO: also store the original (unprocessed) SQL and map SqlParserPos offsets back to it,
 * so snippets can show the full original expression including ARRAY&lt;STRING&gt; type parameters
 * that are currently stripped by preprocessing.
 */
public record ParseResult(SqlNode ast, String preprocessedSql) {}
