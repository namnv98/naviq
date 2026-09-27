package com.sqlctx.schema;

import java.util.List;

public record TableInfo(
        String schema,
        String name,
        String kind,        // table / view / materialized view
        List<ColumnInfo> columns
) {
    public String fullName() {
        return schema + "." + name;
    }
}
