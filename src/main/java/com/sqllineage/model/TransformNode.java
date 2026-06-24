package com.sqllineage.model;

/** An intermediate transform node between a source and output column in the lineage graph. */
public record TransformNode(
    String id,            // "bq_path#cte_name#output_column" — deterministic
    String label,         // actual operator/function name, e.g. "UPPER", "SUM", "CAST", "*"
    String sqlSnippet,    // the raw SQL expression that produced the output column
    String filterContext  // WHERE / HAVING / QUALIFY / GROUP BY snippets from the enclosing SELECT
) implements LineageNode {}
