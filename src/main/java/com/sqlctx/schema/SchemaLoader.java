package com.sqlctx.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        .addColumn(new ColumnInfo(colName, tableName + "." + colName, dataType, notNull));
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

    public static List<String> loadRoles(Connection conn) throws Exception {
        String sql = "SELECT rolname FROM pg_catalog.pg_roles ORDER BY rolname";
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roles.add(rs.getString("rolname"));
            }
        }
        return roles;
    }

    public static List<String> loadLanguages(Connection conn) throws Exception {
        String sql = "SELECT lanname FROM pg_catalog.pg_language ORDER BY lanname";
        List<String> languages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                languages.add(rs.getString("lanname"));
            }
        }
        return languages;
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

    // ════════════════════════════════════════════════════════════════
    // Oracle — catalog hoàn toàn khác pg_catalog, không dùng chung SQL với Postgres ở trên.
    // Chỉ đọc schema (= user) hiện tại đang connect, giống cách bản Postgres chỉ đọc pg_catalog+public.
    // ════════════════════════════════════════════════════════════════

    public static List<SchemaInfo> loadSchemaOracle(Connection conn) throws Exception {
        // Oracle trả tên object/cột KHÔNG QUOTE dưới dạng CHỮ HOA (chuẩn Oracle: định danh không quote được
        // gấp thành chữ hoa trước khi so khớp) - nhưng người dùng gõ SQL kiểu thường (chữ thường) như với
        // Postgres. Hạ về chữ thường khi lưu index để tra cứu theo tên bảng/alias người dùng gõ khớp được,
        // giống hệt cách Postgres đã tự trả về tên chữ thường sẵn từ pg_catalog.
        String schemaName = currentOracleSchema(conn).toLowerCase();

        Map<String, String> kindByTable = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT table_name FROM user_tables");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kindByTable.put(rs.getString("table_name").toLowerCase(), "table");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT view_name FROM user_views");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kindByTable.put(rs.getString("view_name").toLowerCase(), "view");
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT mview_name FROM user_mviews");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kindByTable.put(rs.getString("mview_name").toLowerCase(), "materialized view");
            }
        }

        String sql = """
                    SELECT table_name, column_name, data_type, data_length, data_precision, data_scale, nullable
                    FROM user_tab_columns
                    ORDER BY table_name, column_id
                """;

        Map<String, TableBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                String colName = rs.getString("column_name").toLowerCase();
                String dataType = formatOracleType(rs.getString("data_type"), rs.getInt("data_length"),
                        rs.getObject("data_precision") == null ? null : rs.getInt("data_precision"),
                        rs.getObject("data_scale") == null ? null : rs.getInt("data_scale"));
                boolean notNull = "N".equals(rs.getString("nullable"));

                builders.computeIfAbsent(tableName,
                                k -> new TableBuilder(schemaName, tableName, kindByTable.getOrDefault(tableName, "table")))
                        .addColumn(new ColumnInfo(colName, tableName + "." + colName, dataType, notNull));
            }
        }

        return List.of(new SchemaInfo(schemaName, builders.values().stream().map(TableBuilder::build).toList()));
    }

    private static String currentOracleSchema(Connection conn) throws Exception {
        String schema = conn.getSchema();
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String formatOracleType(String dataType, int length, Integer precision, Integer scale) {
        return switch (dataType) {
            case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "RAW" -> dataType + "(" + length + ")";
            case "NUMBER" -> precision == null ? "NUMBER"
                    : (scale == null || scale == 0 ? "NUMBER(" + precision + ")" : "NUMBER(" + precision + "," + scale + ")");
            default -> dataType;
        };
    }

    /**
     * Oracle không lưu hàm SQL built-in (SYSDATE, NVL, TO_CHAR...) trong catalog user tra được như
     * pg_proc của Postgres - đây là danh sách rút gọn các hàm SQL hay dùng nhất, không đầy đủ 100%.
     */
    public static List<String> loadFunctionsOracle() {
        return List.of(
                "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP",
                "NVL", "NVL2", "DECODE", "COALESCE", "NULLIF", "GREATEST", "LEAST",
                "SUBSTR", "INSTR", "LENGTH", "UPPER", "LOWER", "INITCAP", "TRIM", "LTRIM", "RTRIM",
                "LPAD", "RPAD", "REPLACE", "CONCAT", "REGEXP_LIKE", "REGEXP_SUBSTR", "REGEXP_REPLACE", "REGEXP_INSTR",
                "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP", "CAST",
                "ROUND", "TRUNC", "MOD", "ABS", "CEIL", "FLOOR", "POWER", "SQRT", "SIGN",
                "COUNT", "SUM", "AVG", "MIN", "MAX", "LISTAGG",
                "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "NTILE",
                "EXTRACT", "ADD_MONTHS", "MONTHS_BETWEEN", "LAST_DAY", "NEXT_DAY",
                "USER", "UID", "NVL", "DUMP", "VSIZE"
        );
    }

    /** Danh sách kiểu dữ liệu Oracle hay dùng - Oracle không có catalog liệt kê "mọi kiểu built-in" gọn như pg_type. */
    public static List<String> loadDataTypesOracle() {
        return List.of(
                "NUMBER", "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR",
                "DATE", "TIMESTAMP", "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND",
                "CLOB", "NCLOB", "BLOB", "BFILE", "RAW", "LONG", "LONG RAW",
                "ROWID", "UROWID", "XMLTYPE", "BOOLEAN", "BINARY_FLOAT", "BINARY_DOUBLE"
        );
    }

    private static String kindLabel(String relkind) {
        return switch (relkind) {
            case "r" -> "table";
            case "v" -> "view";
            case "m" -> "materialized view";
            default -> relkind;
        };
    }

    private static class TableBuilder {
        final String schema, name, kind;
        final List<ColumnInfo> columns = new ArrayList<>();

        TableBuilder(String schema, String name, String kind) {
            this.schema = schema;
            this.name = name;
            this.kind = kind;
        }

        void addColumn(ColumnInfo col) {
            columns.add(col);
        }

        TableInfo build() {
            return new TableInfo(schema, name, kind, columns);
        }
    }
}