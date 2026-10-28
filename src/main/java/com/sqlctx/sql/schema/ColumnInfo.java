package com.sqlctx.sql.schema;

public record ColumnInfo(
        String name,
        String fullName,
        String dataType,
        boolean notNull
) {
}
