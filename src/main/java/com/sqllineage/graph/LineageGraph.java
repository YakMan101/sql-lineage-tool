package com.sqllineage.graph;

import com.sqllineage.model.ColumnNode;
import com.sqllineage.model.LineageNode;
import com.sqllineage.model.LineageTree;
import com.sqllineage.model.TransformNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;

/** DAG-backed lineage graph storing column and transform nodes. */
public class LineageGraph {

  private final DirectedAcyclicGraph<LineageNode, DefaultEdge> graph =
      new DirectedAcyclicGraph<>(DefaultEdge.class);

  /** Adds a directed edge between two lineage nodes, inserting vertices as needed. */
  public void addEdge(LineageNode from, LineageNode to) {
    graph.addVertex(from);
    graph.addVertex(to);
    graph.addEdge(from, to);
  }

  /** Returns all column nodes that are direct upstream sources of the given node. */
  public Set<ColumnNode> upstreamColumns(ColumnNode node) {
    return graph.incomingEdgesOf(node).stream()
        .map(graph::getEdgeSource)
        .filter(lineageNode -> lineageNode instanceof TransformNode)
        .flatMap(transform -> graph.incomingEdgesOf(transform).stream()
            .map(graph::getEdgeSource))
        .filter(lineageNode -> lineageNode instanceof ColumnNode)
        .map(lineageNode -> (ColumnNode) lineageNode)
        .collect(Collectors.toSet());
  }

  /** Returns all column nodes that are direct downstream consumers of the given node. */
  public Set<ColumnNode> downstreamColumns(ColumnNode node) {
    return graph.outgoingEdgesOf(node).stream()
        .map(graph::getEdgeTarget)
        .filter(lineageNode -> lineageNode instanceof TransformNode)
        .flatMap(transform -> graph.outgoingEdgesOf(transform).stream()
            .map(graph::getEdgeTarget))
        .filter(lineageNode -> lineageNode instanceof ColumnNode)
        .map(lineageNode -> (ColumnNode) lineageNode)
        .collect(Collectors.toSet());
  }

  /** Returns true if the graph contains the given column node. */
  public boolean contains(ColumnNode node) {
    return graph.containsVertex(node);
  }

  /** Builds a full upstream lineage tree rooted at the given column node. */
  public LineageTree upstreamTree(ColumnNode node) {
    return buildUpstreamTree(node, new HashSet<>());
  }

  /** Builds a full downstream lineage tree rooted at the given column node. */
  public LineageTree downstreamTree(ColumnNode node) {
    return buildDownstreamTree(node, new HashSet<>());
  }

  private TransformNode incomingTransform(ColumnNode node) {
    return graph.incomingEdgesOf(node).stream()
        .map(graph::getEdgeSource)
        .filter(lineageNode -> lineageNode instanceof TransformNode)
        .map(lineageNode -> (TransformNode) lineageNode)
        .findFirst()
        .orElse(null);
  }

  private LineageTree buildUpstreamTree(ColumnNode node, Set<ColumnNode> visited) {
    visited.add(node);
    TransformNode transform = incomingTransform(node);
    List<LineageTree> children = new ArrayList<>();

    for (ColumnNode parent : upstreamColumns(node)) {
      if (!visited.contains(parent)) {
        children.add(buildUpstreamTree(parent, visited));
      }
    }

    return new LineageTree(node, transform, children);
  }

  private LineageTree buildDownstreamTree(ColumnNode node, Set<ColumnNode> visited) {
    visited.add(node);
    TransformNode transform = incomingTransform(node);
    List<LineageTree> children = new ArrayList<>();

    for (ColumnNode child : downstreamColumns(node)) {
      if (!visited.contains(child)) {
        children.add(buildDownstreamTree(child, visited));
      }
    }

    return new LineageTree(node, transform, children);
  }

  /** Returns all nodes in the graph. */
  public Set<LineageNode> vertices() {
    return graph.vertexSet();
  }

  /** Returns all edges in the graph. */
  public Set<DefaultEdge> edges() {
    return graph.edgeSet();
  }

  /** Returns the source node of an edge. */
  public LineageNode edgeSource(DefaultEdge edge) {
    return graph.getEdgeSource(edge);
  }

  /** Returns the target node of an edge. */
  public LineageNode edgeTarget(DefaultEdge edge) {
    return graph.getEdgeTarget(edge);
  }
}
