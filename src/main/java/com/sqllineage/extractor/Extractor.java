package com.sqllineage.extractor;

import com.sqllineage.graph.LineageGraph;
import com.sqllineage.model.ColumnNode;
import com.sqllineage.model.TableEntry;
import com.sqllineage.model.TransformNode;
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
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;

/** Walks Calcite ASTs and extracts column-level lineage edges into a LineageGraph. */
public class Extractor {

  private final LineageGraph graph;

  /** Constructs an Extractor that writes edges into the given graph. */
  public Extractor(LineageGraph graph) {
    this.graph = graph;
  }

  /** Extracts lineage for a single model and adds edges to the shared graph. */
  public void extractTable(TableEntry table, SqlNode ast) {
    extractStatement(ast, table.bqTablePath(), new HashMap<>());
  }

  private void extractStatement(SqlNode node, String tableId, Map<String, String> cteScope) {
    switch (node.getKind()) {
      case WITH -> extractWith((SqlWith) node, tableId, cteScope);
      case SELECT -> extractSelect((SqlSelect) node, tableId, cteScope);
      case ORDER_BY -> {
        SqlOrderBy orderBy = (SqlOrderBy) node;
        extractStatement(orderBy.query, tableId, cteScope);
      }
      default -> { /* unsupported statement kind — skip silently */ }
    }
  }

  private void extractWith(SqlWith with, String tableId, Map<String, String> cteScope) {
    Map<String, String> localScope = new HashMap<>(cteScope);
    for (SqlNode item : with.withList) {
      SqlWithItem withItem = (SqlWithItem) item;
      String cteName = withItem.name.getSimple();
      String cteTableId = tableId + "#" + cteName;
      localScope.put(cteName, cteTableId);
      extractStatement(withItem.query, cteTableId, localScope);
    }
    extractStatement(with.body, tableId, localScope);
  }

  private void extractSelect(SqlSelect select, String tableId, Map<String, String> cteScope) {
    // TODO: capture select.getWhere() as a SQL snippet and attach it to each TransformNode
    // produced by this SELECT. Useful context when a user expands a CTE transformation in a UI —
    // the WHERE clause doesn't affect what produced a column, but it does affect which rows are
    // visible, which is relevant for understanding the full logic of the transform.
    Map<String, String> sourceScope = buildSourceScope(select.getFrom(), cteScope);
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
      String sqlSnippet = unparse(expr);
      ColumnNode output = new ColumnNode(tableId, outputName);
      String transformId = tableId + "#" + outputName;
      TransformNode transform = new TransformNode(transformId, label, sqlSnippet);
      for (ColumnNode input : inputs) {
        graph.addEdge(input, transform);
      }
      graph.addEdge(transform, output);
    }
  }

  private Map<String, String> buildSourceScope(SqlNode from, Map<String, String> cteScope) {
    Map<String, String> scope = new HashMap<>();
    if (from == null) {
      return scope;
    }
    switch (from.getKind()) {
      case IDENTIFIER -> {
        SqlIdentifier identifier = (SqlIdentifier) from;
        String tableId = String.join(".", identifier.names);
        String resolved = cteScope.getOrDefault(
            identifier.names.get(identifier.names.size() - 1), tableId);
        scope.put(identifier.names.get(identifier.names.size() - 1), resolved);
        scope.put("", resolved);
      }
      case AS -> {
        SqlBasicCall asCall = (SqlBasicCall) from;
        SqlNode source = asCall.operand(0);
        String alias = ((SqlIdentifier) asCall.operand(1)).getSimple();
        String resolved = resolveFromSource(source, cteScope);
        scope.put(alias, resolved);
        scope.put("", resolved);
      }
      case JOIN -> {
        SqlJoin join = (SqlJoin) from;
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
      Map<String, String> sourceScope) {
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
            qualifier, String.join(".", identifier.names.subList(0, identifier.names.size() - 1)));
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

  private String unparse(SqlNode expr) {
    SqlPrettyWriter writer = new SqlPrettyWriter();
    expr.unparse(writer, 0, 0);
    return writer.toString();
  }
}
