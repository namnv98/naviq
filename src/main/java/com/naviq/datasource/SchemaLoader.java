package com.naviq.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class SchemaLoader {

    public static List<SchemaInfo> loadSchema(Connection conn) throws Exception {
        String sql = """
                    SELECT
                        n.nspname   AS schema_name,
                        c.relname   AS table_name,
                        c.relkind   AS rel_kind,
                        a.attname   AS column_name,
                        pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type,
                        a.attnotnull AS not_null
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid
                    WHERE c.relkind IN ('r','v','m')
                      AND a.attnum > 0
                      AND NOT a.attisdropped
                    ORDER BY n.nspname, c.relname, a.attnum
                """;

        // schema → table → columns
        Map<String, Map<String, TableBuilder>> builders = new LinkedHashMap<>();

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String kind = kindLabel(rs.getString("rel_kind"));
                String colName = rs.getString("column_name");
                String dataType = rs.getString("data_type");
                boolean notNull = rs.getBoolean("not_null");

                builders
                        .computeIfAbsent(schemaName, k -> new LinkedHashMap<>())
                        .computeIfAbsent(tableName, k -> new TableBuilder(schemaName, tableName, kind))
                        .addColumn(new DBColumnInfo(colName, tableName + "." + colName, dataType, notNull));
            }
        }

        return builders.entrySet().stream()
                .map(e -> new SchemaInfo(
                        e.getKey(),
                        e.getValue().values().stream()
                                .map(TableBuilder::build)
                                .toList()
                ))
                .toList();
    }

    public static List<String> loadFunctions(Connection conn) throws Exception {
        String sql = """
                    SELECT DISTINCT proname
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname IN ('pg_catalog', 'public')
                      AND p.prokind IN ('f', 'a')     -- f=function, a=aggregate
                      AND NOT p.proisstrict IS NULL
                    ORDER BY proname
                """;

        List<String> functions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                functions.add(rs.getString("proname"));
            }
        }
        return functions;
    }

    public static List<String> loadDataTypes(Connection conn) throws Exception {
        String sql = """
                    SELECT typname
                    FROM pg_catalog.pg_type
                    WHERE typtype IN ('b', 'd')        -- base type và domain
                      AND typelem = 0                  -- bỏ array types (_int4, _text...)
                      AND typnamespace IN (
                          SELECT oid FROM pg_namespace
                          WHERE nspname IN ('pg_catalog', 'public')
                      )
                    ORDER BY typname
                """;

        List<String> types = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                types.add(rs.getString("typname"));
            }
        }
        return types;
    }

    private static String kindLabel(String relkind) {
        return switch (relkind) {
            case "r" -> "table";
            case "v" -> "view";
            case "m" -> "materialized view";
            default -> relkind;
        };
    }

    // builder nội bộ để gom columns
    private static class TableBuilder {
        final String schema, name, kind;
        final List<DBColumnInfo> columns = new ArrayList<>();

        TableBuilder(String schema, String name, String kind) {
            this.schema = schema;
            this.name = name;
            this.kind = kind;
        }

        void addColumn(DBColumnInfo col) {
            columns.add(col);
        }

        TableInfo build() {
            return new TableInfo(schema, name, kind, columns);
        }
    }


    // ColumnInfo.java

    public record DBColumnInfo(
            String name,
            String fullName,
            String dataType,
            boolean notNull
    ) {
    }

    // TableInfo.java
    public record TableInfo(
            String schema,
            String name,
            String kind,        // table / view / materialized view
            List<DBColumnInfo> columns
    ) {
        public String fullName() {
            return schema + "." + name;
        }
    }

    // SchemaInfo.java
    public record SchemaInfo(
            String name,
            List<TableInfo> tables
    ) {
    }
}