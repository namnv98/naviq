package com.naviq.completion.model;

import org.apache.commons.lang3.StringUtils;

public record ColumnInfo(
        String name,
        String fullName,
        String table,
        String type,
        String tableAlias,
        String alias
) {
    public ColumnInfo(String name) {
        this(name, "", "", "", "", "");
    }

    public ColumnInfo(String name, String fullName, String table, String type) {
        this(name, fullName, table, type, "", "");
    }

    public ColumnInfo withAlias(String alias) {
        return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
    }

    public ColumnInfo withTableAlias(String tableAlias) {
        return new ColumnInfo(name, fullName, table, type, tableAlias, alias);
    }

    public String nameAlias() {
        if (StringUtils.isEmpty(alias)) {
            return name;
        }
        return alias;
    }
}
