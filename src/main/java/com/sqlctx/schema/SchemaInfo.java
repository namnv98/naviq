package com.sqlctx.schema;

import java.util.List;

public record SchemaInfo(
        String name,
        List<TableInfo> tables
) {
}
