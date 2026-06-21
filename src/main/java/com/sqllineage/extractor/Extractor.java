package com.sqllineage.extractor;

import com.sqllineage.model.TableEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Extractor {

    public static List<String> gatherColumnsForTable(TableEntry table) {
        // TODO: parse compiled SQL at table.compiledFilePath() and extract SELECT column list
        return List.of();
    }

    public static Map<TableEntry, List<String>> buildTableColumnMap(List<TableEntry> tables) {
        Map<TableEntry, List<String>> tableColumnMap = new HashMap<>();
        tables.forEach(table -> tableColumnMap.put(table, gatherColumnsForTable(table)));
        return tableColumnMap;
    }

}
