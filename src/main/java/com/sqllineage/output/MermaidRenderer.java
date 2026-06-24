package com.sqllineage.output;

import com.sqllineage.model.ColumnNode;
import com.sqllineage.model.LineageTree;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Renders a {@link LineageTree} as a Mermaid flowchart diagram.
 *
 * <p>Columns are grouped into subgraphs by table/CTE. Transforms between columns are shown as
 * hexagon nodes carrying a truncated SQL snippet, so users can expand to see the full expression.
 */
public class MermaidRenderer {

  private static final int SNIPPET_MAX_LEN = 500;

  /** Returns a Mermaid flowchart string for the given lineage tree. */
  public String render(LineageTree tree) {
    StringBuilder sb = new StringBuilder();
    // Collect edges and nodes in traversal order before grouping into subgraphs
    Set<String> edgeLines = new LinkedHashSet<>();
    Set<String> transformLines = new LinkedHashSet<>();
    collectEdges(tree, edgeLines, transformLines);

    sb.append("flowchart LR\n");

    // Emit transform node shapes (hexagons) before subgraphs so they sit outside any group
    for (String transformLine : transformLines) {
      sb.append("  ").append(transformLine).append("\n");
    }

    // Group column nodes into subgraphs by their table/CTE id
    Set<String> tableIds = new LinkedHashSet<>();
    collectTableIds(tree, tableIds);
    for (String tableId : tableIds) {
      String subgraphLabel = tableId.contains(".")
          ? tableId.substring(tableId.lastIndexOf('.') + 1)
          : tableId;
      sb.append("  subgraph ").append(mermaidQuote(tableId))
          .append("[").append(subgraphLabel).append("]\n");
      emitColumnNode(tree, tableId, sb);
      sb.append("  end\n");
    }

    // Emit edges last
    for (String edgeLine : edgeLines) {
      sb.append("  ").append(edgeLine).append("\n");
    }

    return sb.toString();
  }

  private void collectEdges(
      LineageTree tree, Set<String> edgeLines, Set<String> transformLines) {
    for (LineageTree child : tree.children()) {
      String childColId = nodeId(child.node());
      String parentColId = nodeId(tree.node());

      if (tree.transform() != null) {
        String transformNodeId = transformId(tree.transform().id());
        String nodeLabel = buildTransformLabel(tree);
        // Hexagon shape: {{label}}
        transformLines.add(transformNodeId + "{{\"`" + escapeSnippet(nodeLabel) + "`\"}}");
        edgeLines.add(childColId + " --> " + transformNodeId);
        edgeLines.add(transformNodeId + " --> " + parentColId);
      } else {
        edgeLines.add(childColId + " --> " + parentColId);
      }

      collectEdges(child, edgeLines, transformLines);
    }
  }

  private void collectTableIds(LineageTree tree, Set<String> tableIds) {
    tableIds.add(tree.node().tableId());
    for (LineageTree child : tree.children()) {
      collectTableIds(child, tableIds);
    }
  }

  private void emitColumnNode(LineageTree tree, String tableId, StringBuilder sb) {
    if (tree.node().tableId().equals(tableId)) {
      String colId = nodeId(tree.node());
      sb.append("    ").append(colId)
          .append("[\"").append(tree.node().columnName()).append("\"]\n");
    }
    for (LineageTree child : tree.children()) {
      emitColumnNode(child, tableId, sb);
    }
  }

  private String nodeId(ColumnNode node) {
    return sanitiseNonAlpha(node.tableId() + "_" + node.columnName());
  }

  private String transformId(String rawId) {
    return "t_" + sanitiseNonAlpha(rawId);
  }

  private String sanitiseNonAlpha(String raw) {
    return raw.replaceAll("[^a-zA-Z0-9_]", "_");
  }

  private String mermaidQuote(String label) {
    return "\"" + label.replace("\"", "'") + "\"";
  }

  private String buildTransformLabel(LineageTree tree) {
    String snippet = truncate(tree.sqlSnippet());
    String filterContext = tree.transform() != null ? tree.transform().filterContext() : "";
    if (filterContext == null || filterContext.isEmpty()) {
      return snippet;
    }

    return snippet + "\n\n>> " + filterContext.replace("\n", "\n>> ");
  }

  private String escapeSnippet(String snippet) {
    return snippet.replace("\"", "'").replace("`", "'");
  }

  private String truncate(String snippet) {
    if (snippet == null || snippet.isEmpty()) {
      return "";
    }

    return snippet.length() > SNIPPET_MAX_LEN
        ? snippet.substring(0, SNIPPET_MAX_LEN) + "…"
        : snippet;
  }
}
