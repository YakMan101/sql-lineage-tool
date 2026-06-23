package com.sqllineage.model;

import java.util.List;

/**
 * A tree node representing a column's lineage, with its upstream or downstream children.
 * {@code via} is the transform label (e.g. "UPPER", "CAST", "+"), or empty for passthroughs.
 */
public record LineageTree(ColumnNode node, String via, List<LineageTree> children) {}
