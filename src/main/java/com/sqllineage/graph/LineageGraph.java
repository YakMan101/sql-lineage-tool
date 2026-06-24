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
        .filter(n -> n instanceof TransformNode)
        .flatMap(t -> graph.incomingEdgesOf(t).stream()
        .map(graph::getEdgeSource))
        .filter(n -> n instanceof ColumnNode)
        .map(n -> (ColumnNode) n)
        .collect(Collectors.toSet());
  }

  /** Returns all column nodes that are direct downstream consumers of the given node. */
  public Set<ColumnNode> downstreamColumns(ColumnNode node) {
    return graph.outgoingEdgesOf(node).stream()
        .map(graph::getEdgeTarget)
        .filter(n -> n instanceof TransformNode)
        .flatMap(t -> graph.outgoingEdgesOf(t).stream()
        .map(graph::getEdgeTarget))
        .filter(n -> n instanceof ColumnNode)
        .map(n -> (ColumnNode) n)
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

  private String incomingTransformLabel(ColumnNode node) {
    return graph.incomingEdgesOf(node).stream()
        .map(graph::getEdgeSource)
        .filter(n -> n instanceof TransformNode)
        .map(n -> ((TransformNode) n).label())
        .findFirst()
        .orElse("");
  }

  private LineageTree buildUpstreamTree(ColumnNode node, Set<ColumnNode> visited) {
    visited.add(node);
    String via = incomingTransformLabel(node);
    List<LineageTree> children = new ArrayList<>();
    
    for (ColumnNode parent : upstreamColumns(node)) {
      if (!visited.contains(parent)) {
        children.add(buildUpstreamTree(parent, visited));
      }
    }
    
    return new LineageTree(node, via, children);
  }

  private LineageTree buildDownstreamTree(ColumnNode node, Set<ColumnNode> visited) {
    visited.add(node);
    String via = incomingTransformLabel(node);
    List<LineageTree> children = new ArrayList<>();
    
    for (ColumnNode child : downstreamColumns(node)) {
      if (!visited.contains(child)) {
        children.add(buildDownstreamTree(child, visited));
      }
    }
    
    return new LineageTree(node, via, children);
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
