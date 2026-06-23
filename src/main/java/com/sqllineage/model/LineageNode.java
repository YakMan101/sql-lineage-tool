package com.sqllineage.model;

/** Sealed marker interface for all nodes in the lineage graph. */
public sealed interface LineageNode permits ColumnNode, TransformNode {}
