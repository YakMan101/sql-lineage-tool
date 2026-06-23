package com.sqllineage.model;

/** Categories of SQL transformations applied to a column. */
public enum TransformationType {
  DIRECT,
  CAST,
  CONCAT,
  COALESCE,
  CASE,
  FUNCTION,
  EXPRESSION
}
