package com.sqllineage.extractor;

import com.sqllineage.graph.LineageGraph;
import com.sqllineage.model.ColumnNode;
import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TransformNode;
import com.sqllineage.parser.ParseResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.parser.SqlParserPos;

/** Walks Calcite ASTs and extracts column-level lineage edges into a LineageGraph. */
public class Extractor {

  private final LineageGraph graph;

  /** Constructs an Extractor that writes edges into the given graph. */
  public Extractor(LineageGraph graph) {
    this.graph = graph;
  }

  /** Extracts lineage for a single model and adds edges to the shared graph. */
  public void extractTable(TableEntry table, ParseResult parseResult) {
    extractStatement(
        parseResult.ast(), table.bqTablePath(), new HashMap<>(), parseResult.preprocessedSql(),
        null);
  }

  private void extractStatement(
      SqlNode node, String tableId, Map<String, String> cteScope, String sql,
      SqlNodeList orderList) {
    switch (node.getKind()) {
      case WITH -> extractWith((SqlWith) node, tableId, cteScope, sql);
      case SELECT -> extractSelect((SqlSelect) node, tableId, cteScope, sql, orderList);
      case ORDER_BY -> { // ORDER BY is a wrapper around a SELECT statement
        SqlOrderBy orderBy = (SqlOrderBy) node;
        extractStatement(orderBy.query, tableId, cteScope, sql, orderBy.orderList);
      }
      default -> { /* unsupported statement kind — skip silently */ }
    }
  }

  private void extractWith(
      SqlWith with, String tableId, Map<String, String> cteScope, String sql) {
    Map<String, String> localScope = new HashMap<>(cteScope);

    for (SqlNode item : with.withList) {
      SqlWithItem withItem = (SqlWithItem) item;
      String cteName = withItem.name.getSimple();
      String cteTableId = tableId + "#" + cteName;
      localScope.put(cteName, cteTableId);
      extractStatement(withItem.query, cteTableId, localScope, sql, null);
    }

    extractStatement(with.body, tableId, localScope, sql, null);
  }

  private void extractSelect(
      SqlSelect select, String tableId, Map<String, String> cteScope, String sql,
      SqlNodeList orderList) {
    Map<String, String> sourceScope = buildSourceScope(select.getFrom(), cteScope);
    String filterContext = buildFilterContext(select, orderList, sql);
    
    for (SqlNode selectItem : select.getSelectList()) {
      String outputName;
      SqlNode expr;

      if (selectItem.getKind() == SqlKind.AS) {
        SqlBasicCall asCall = (SqlBasicCall) selectItem;
        expr = asCall.operand(0);
        outputName = ((SqlIdentifier) asCall.operand(1)).getSimple();
      } else if (selectItem instanceof SqlIdentifier identifier) {
        expr = identifier;
        outputName = identifier.names.get(identifier.names.size() - 1);
      } else {
        continue;
      }

      List<ColumnNode> inputs = collectInputColumns(expr, tableId, cteScope, sourceScope);
      String label = classifyTransform(expr);
      String sqlSnippet = sliceSnippet(expr, sql);
      ColumnNode output = new ColumnNode(tableId, outputName);
      String transformId = tableId + "#" + outputName;
      TransformNode transform = new TransformNode(transformId, label, sqlSnippet, filterContext);

      for (ColumnNode input : inputs) {
        graph.addEdge(input, transform);
      }

      graph.addEdge(transform, output);
    }
  }

  private String buildFilterContext(SqlSelect select, SqlNodeList orderList, String sql) {
    StringBuilder context = new StringBuilder();
    appendClause(context, "WHERE", select.getWhere(), sql);
    appendClause(context, "GROUP BY", select.getGroup(), sql);
    appendClause(context, "HAVING", select.getHaving(), sql);
    appendClause(context, "QUALIFY", select.getQualify(), sql);
    appendClause(context, "ORDER BY", orderList, sql);

    return context.toString().trim();
  }

  private void appendClause(StringBuilder context, String label, SqlNode clause, String sql) {
    if (clause == null) {
      return;
    }
    String snippet = sliceSnippet(clause, sql);
    if (!snippet.isEmpty()) {
      context.append(label).append(" ").append(snippet).append("\n");
    }
  }

  private Map<String, String> buildSourceScope(SqlNode fromClause, Map<String, String> cteScope) {
    Map<String, String> scope = new HashMap<>();

    if (fromClause == null) {
      return scope;
    }

    switch (fromClause.getKind()) {
      case IDENTIFIER -> {
        SqlIdentifier identifier = (SqlIdentifier) fromClause;
        String tableId = String.join(".", identifier.names);
        String resolved = cteScope.getOrDefault(
            identifier.names.get(identifier.names.size() - 1), tableId);
        scope.put(identifier.names.get(identifier.names.size() - 1), resolved);
        scope.put("", resolved);
      }
      case AS -> {
        SqlBasicCall asCall = (SqlBasicCall) fromClause;
        SqlNode source = asCall.operand(0);
        String alias = ((SqlIdentifier) asCall.operand(1)).getSimple();
        String resolved = resolveFromSource(source, cteScope);
        scope.put(alias, resolved);
        scope.put("", resolved);
      }
      case JOIN -> {
        SqlJoin join = (SqlJoin) fromClause;
        scope.putAll(buildSourceScope(join.getLeft(), cteScope));
        scope.putAll(buildSourceScope(join.getRight(), cteScope));
        scope.remove("");
      }
      default -> { /* subqueries, TVFs — skip for now */ }
    }
    
    return scope;
  }

  private String resolveFromSource(SqlNode source, Map<String, String> cteScope) {
    if (source instanceof SqlIdentifier identifier) {
      String lastName = identifier.names.get(identifier.names.size() - 1);
      return cteScope.getOrDefault(lastName, String.join(".", identifier.names));
    }
    
    return "#subquery";
  }

  private List<ColumnNode> collectInputColumns(
      SqlNode expr,
      String tableId,
      Map<String, String> cteScope,
      Map<String, String> sourceScope) {
    return switch (expr.getKind()) {
      case IDENTIFIER -> resolveIdentifier((SqlIdentifier) expr, cteScope, sourceScope);
      case AS -> collectInputColumns(
          ((SqlBasicCall) expr).operand(0), tableId, cteScope, sourceScope);
      case CAST -> collectInputColumns(
          ((SqlBasicCall) expr).operand(0), tableId, cteScope, sourceScope);
      case CASE -> collectFromCase((SqlCase) expr, tableId, cteScope, sourceScope);
      case LITERAL -> List.of();
      default -> collectFromCall(expr, tableId, cteScope, sourceScope);
    };
  }

  private List<ColumnNode> resolveIdentifier(
      SqlIdentifier identifier,
      Map<String, String> cteScope,
      Map<String, String> sourceScope
  ) {
    String column = identifier.names.get(identifier.names.size() - 1);
    
    if (identifier.names.size() == 1) {
      String sourceTableId = sourceScope.get("");
      if (sourceTableId != null) {
        return List.of(new ColumnNode(sourceTableId, column));
      }

      return List.of();
    }
    
    String qualifier = identifier.names.get(0);
    String resolvedTableId = cteScope.containsKey(qualifier)
        ? cteScope.get(qualifier)
        : sourceScope.getOrDefault(
            qualifier, String.join(
              ".", identifier.names.subList(0, identifier.names.size() - 1))
            );
    
    return List.of(new ColumnNode(resolvedTableId, column));
  }

  private List<ColumnNode> collectFromCase(
      SqlCase sqlCase,
      String tableId,
      Map<String, String> cteScope,
      Map<String, String> sourceScope) {
    List<ColumnNode> results = new ArrayList<>();
    
    for (SqlNode when : sqlCase.getWhenOperands()) {
      results.addAll(collectInputColumns(when, tableId, cteScope, sourceScope));
    }
    
    for (SqlNode then : sqlCase.getThenOperands()) {
      results.addAll(collectInputColumns(then, tableId, cteScope, sourceScope));
    }
    
    if (sqlCase.getElseOperand() != null) {
      results.addAll(
          collectInputColumns(sqlCase.getElseOperand(), tableId, cteScope, sourceScope));
    }
    
    return results;
  }

  private List<ColumnNode> collectFromCall(
      SqlNode expr,
      String tableId,
      Map<String, String> cteScope,
      Map<String, String> sourceScope) {
    if (!(expr instanceof SqlCall call)) {
      return List.of();
    }
    
    List<ColumnNode> results = new ArrayList<>();
    
    for (SqlNode operandNode : call.getOperandList()) {
      if (operandNode != null) {
        results.addAll(collectInputColumns(operandNode, tableId, cteScope, sourceScope));
      }
    }
    
    return results;
  }

  private String classifyTransform(SqlNode expr) {
    return switch (expr.getKind()) {
      case IDENTIFIER -> "";
      case CAST -> "CAST";
      case CASE -> "CASE";
      case OTHER_FUNCTION -> operatorName(expr);
      case PLUS -> "+";
      case MINUS -> "-";
      case TIMES -> "*";
      case DIVIDE -> "/";
      default -> operatorName(expr);
    };
  }

  private String operatorName(SqlNode expr) {
    if (expr instanceof SqlCall call) {
      return call.getOperator().getName().toUpperCase();
    }
    
    return expr.getKind().name();
  }

  private String sliceSnippet(SqlNode expr, String sql) {
    SqlParserPos pos = expr.getParserPosition();
    
    if (pos == null || pos.equals(SqlParserPos.ZERO)) {
      return "";
    }
    
    String[] lines = sql.split("\n", -1);
    int startLine = pos.getLineNum() - 1;
    int endLine = pos.getEndLineNum() - 1;
    int startCol = pos.getColumnNum() - 1;
    int endCol = pos.getEndColumnNum();
    
    if (startLine < 0 || startLine >= lines.length || endLine >= lines.length) {
      return "";
    }
    
    if (startLine == endLine) {
      return lines[startLine].substring(startCol, Math.min(endCol, lines[startLine].length()));
    }
    
    StringBuilder snippet = new StringBuilder();
    snippet.append(lines[startLine].substring(startCol)).append("\n");
    
    for (int lineIndex = startLine + 1; lineIndex < endLine; lineIndex++) {
      snippet.append(lines[lineIndex]).append("\n");
    }
    
    snippet.append(lines[endLine], 0, Math.min(endCol, lines[endLine].length()));

    return snippet.toString();
  }
}
