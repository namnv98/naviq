package com.sqlctx.schema;

public record ColumnInfo(
        String name,
        String fullName,
        String dataType,
        boolean notNull
) {
}
