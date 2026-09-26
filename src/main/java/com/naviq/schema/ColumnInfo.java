package com.naviq.schema;

public record ColumnInfo(
        String name,
        String fullName,
        String dataType,
        boolean notNull
) {
}
