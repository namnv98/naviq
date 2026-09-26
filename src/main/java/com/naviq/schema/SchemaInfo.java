package com.naviq.schema;

import java.util.List;

public record SchemaInfo(
        String name,
        List<TableInfo> tables
) {
}
