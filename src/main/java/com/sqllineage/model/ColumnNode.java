package com.sqllineage.model;

/** A single column in a table or CTE, used as a node in the lineage graph. */
public record ColumnNode(
    String tableId,   // BQ path, or "bq_path#cte_name" for CTE intermediates
    String columnName
) implements LineageNode {}
