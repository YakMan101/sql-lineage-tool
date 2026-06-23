package com.sqllineage.model;

/** Metadata for a single dbt model, seed, or source table. */
public record TableEntry(
    String bqTablePath,
    String localFilePath,
    String compiledFilePath,
    TableType tableType
) {}
