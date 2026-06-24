package com.sqllineage.model;

import java.util.List;

/**
 * A tree node representing a column's lineage, with its upstream or downstream children.
 * {@code transform} carries the full transform metadata (label + SQL snippet), or null for
 * direct passthroughs with no intermediate transform.
 */
public record LineageTree(
    ColumnNode node,
    TransformNode transform,
    List<LineageTree> children
) {
  /** Convenience accessor — returns the transform label, or empty string for passthroughs. */
  public String via() {
    return transform != null ? transform.label() : "";
  }

  /** Convenience accessor — returns the SQL snippet, or empty string for passthroughs. */
  public String sqlSnippet() {
    return transform != null ? transform.sqlSnippet() : "";
  }
}
