package com.sqllineage.model;

public record TableEntry(
    String bqTablePath,
    String localFilePath,
    String compiledFilePath,
    TableType tableType
) {}
