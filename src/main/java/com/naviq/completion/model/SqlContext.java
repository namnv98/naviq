package com.naviq.completion.model;

import java.util.List;
import java.util.Map;

public record SqlContext(
        Map<String, String> aliasMap,
        Map<String, List<ColumnInfo>> cteColumns,
        List<String> tables,
        List<ColumnInfo> columns
) {
}