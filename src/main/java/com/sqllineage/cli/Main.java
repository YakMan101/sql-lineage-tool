package com.sqllineage.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqllineage.extractor.Extractor;
import com.sqllineage.graph.LineageGraph;
import com.sqllineage.ingestion.Ingestion;
import com.sqllineage.model.ColumnNode;
import com.sqllineage.model.LineageTree;
import com.sqllineage.model.TableEntry;
import com.sqllineage.output.MermaidRenderer;
import com.sqllineage.parser.Parser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;

/** Entry point for the SQL lineage CLI. */
public class Main {

  private static final String RESET = "\033[0m";
  private static final String DIM = "\033[2m";
  private static final String BOLD_CYAN = "\033[1;36m";
  private static final String YELLOW = "\033[33m";

  /** Runs the lineage tool. */
  public static void main(String[] args) throws IOException, SqlParseException {
    // TODO: Refactor some of the below code to their own methods for readability
    if (args.length < 2) {
      System.err.println("Usage: lineage <table> <column> [upstream|downstream] [text|mermaid]");
      System.err.println("  e.g. lineage stg_orders IS_CLOSED upstream mermaid");
      System.exit(1);
    }

    String tableArg = args[0];
    String columnArg = args[1].toUpperCase();
    String direction = args.length >= 3 ? args[2].toLowerCase() : "upstream";
    String format = args.length >= 4 ? args[3].toLowerCase() : "text";

    LineageGraph graph = buildGraph();
    
    ColumnNode target = graph.vertices().stream()
        .filter(node -> node instanceof ColumnNode col
            && col.columnName().equalsIgnoreCase(columnArg)
            && (col.tableId().equalsIgnoreCase(tableArg)
                || col.tableId().endsWith("." + tableArg)
                || col.tableId().endsWith("#" + tableArg)))
        .map(node -> (ColumnNode) node)
        .filter(col -> !col.tableId().contains("#"))
        .findFirst()
        .orElse(null);

    if (target == null) {
      System.err.println("Column not found: " + tableArg + "." + columnArg);
      System.err.println("Available columns:");
      graph.vertices().stream()
          .filter(node -> node instanceof ColumnNode col && !col.tableId().contains("#"))
          .map(node -> (ColumnNode) node)
          .sorted((left, right) -> (left.tableId() + left.columnName())
              .compareToIgnoreCase(right.tableId() + right.columnName()))
          .forEach(col -> System.err.println("  " + col.tableId() + "." + col.columnName()));
      System.exit(1);
      return;
    }

    LineageTree tree = direction.equals("downstream")
        ? graph.downstreamTree(target)
        : graph.upstreamTree(target);

    if (tree.children().isEmpty()) {
      System.out.println(formatColumn(target) + "  (no " + direction + " lineage)");
      return;
    }

    if (format.equals("mermaid")) {
      System.out.println(new MermaidRenderer().render(tree));
    } else {
      printTree(tree, "", "");
    }
  }

  private static void printTree(LineageTree tree, String connector, String prefix) {
    String transformLabel = !tree.via().isEmpty()
        ? "  " + YELLOW + "[" + tree.via() + "]" + RESET
        : "";
    System.out.println(DIM + connector + RESET + formatColumn(tree.node()) + transformLabel);

    List<LineageTree> children = tree.children();
    
    for (int index = 0; index < children.size(); index++) {
      boolean isLast = index == children.size() - 1;
      String childConnector = prefix + (isLast ? "└─ " : "├─ ");
      String childPrefix = prefix + (isLast ? "   " : "│  ");
      printTree(children.get(index), childConnector, childPrefix);
    }
  }

  private static String formatColumn(ColumnNode node) {
    String tablePrefix = node.tableId().contains(".")
        ? node.tableId().substring(0, node.tableId().lastIndexOf('.') + 1)
        : "";
    String tableName = node.tableId().contains(".")
        ? node.tableId().substring(node.tableId().lastIndexOf('.') + 1)
        : node.tableId();
    return DIM + tablePrefix + RESET + tableName + "." + BOLD_CYAN + node.columnName() + RESET;
  }

  private static LineageGraph buildGraph() throws IOException, SqlParseException {
    JsonNode manifest = Ingestion.readManifestJson("dbt_project/target/manifest.json");
    List<TableEntry> tables = Ingestion.gatherTables(manifest, "dbt_project");

    Parser parser = new Parser();
    Map<TableEntry, SqlNode> asts = parser.parseCompiledModels(tables);

    LineageGraph graph = new LineageGraph();
    Extractor extractor = new Extractor(graph);
    
    for (Map.Entry<TableEntry, SqlNode> entry : asts.entrySet()) {
      extractor.extractTable(entry.getKey(), entry.getValue());
    }
    
    return graph;
  }
}
