package com.sqlctx.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * Hàm người dùng thật sự gọi trong SQL: bỏ hàm nhận/trả kiểu nội bộ (handler, hàm trigger...), hàm chỉ là phần
     * cài đặt của toán tử ("int2div" cho "/"), hàm I/O của kiểu ("int4in", "int2send") và hàm bước của aggregate
     * ("int8dec"). Postgres 18: ~2800 tên hàm còn ~1070 - gõ "id" không còn ra "index_am_handler_in".
     */
    private static final String PG_CALLABLE_FUNCTION = """
            NOT EXISTS (SELECT 1 FROM unnest(p.proargtypes) AS a(t) WHERE a.t IN ('internal'::regtype, 'cstring'::regtype))
                      AND p.prorettype NOT IN ('internal'::regtype, 'cstring'::regtype, 'trigger'::regtype,
                          'event_trigger'::regtype, 'language_handler'::regtype, 'fdw_handler'::regtype,
                          'index_am_handler'::regtype, 'tsm_handler'::regtype, 'table_am_handler'::regtype)
                      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_operator o WHERE o.oprcode::oid = p.oid)
                      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_type ty WHERE p.oid IN (ty.typinput::oid,
                          ty.typoutput::oid, ty.typreceive::oid, ty.typsend::oid, ty.typmodin::oid, ty.typmodout::oid,
                          ty.typanalyze::oid))
                      AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_aggregate ag WHERE p.oid IN (ag.aggtransfn::oid,
                          ag.aggfinalfn::oid, ag.aggcombinefn::oid, ag.aggserialfn::oid, ag.aggdeserialfn::oid,
                          ag.aggmtransfn::oid, ag.aggminvtransfn::oid, ag.aggmfinalfn::oid))""";

    public static List<String> loadFunctions(Connection conn) throws Exception {
        String sql = """
                    SELECT DISTINCT proname
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ANY (pg_catalog.current_schemas(true))  -- gọi được bằng tên trần
                      AND p.prokind IN ('f', 'a')     -- f=function, a=aggregate
                      AND NOT p.proisstrict IS NULL
                      AND %s
                    ORDER BY proname
                """.formatted(PG_CALLABLE_FUNCTION);

        List<String> functions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                functions.add(rs.getString("proname"));
            }
        }
        return functions;
    }

    /**
     * Chữ ký hàm gọi được bằng tên trần: overload ít tham số nhất, kèm "+N" nếu còn N overload khác - menu chỉ có
     * 1 dòng cho mỗi tên (SuggestFilter khử trùng theo tên).
     */
    public static Map<String, String> loadFunctionSignatures(Connection conn) throws Exception {
        String sql = """
                    SELECT p.proname,
                           pg_catalog.pg_get_function_arguments(p.oid) AS args,
                           pg_catalog.pg_get_function_result(p.oid) AS result
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ANY (pg_catalog.current_schemas(true))
                      AND p.prokind IN ('f', 'a')
                      AND %s
                    ORDER BY p.proname, p.pronargs
                """.formatted(PG_CALLABLE_FUNCTION);

        Map<String, String> first = new LinkedHashMap<>();
        Map<String, Integer> overloads = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("proname").toLowerCase();
                first.putIfAbsent(name, "(" + rs.getString("args") + ") → " + rs.getString("result"));
                overloads.merge(name, 1, Integer::sum);
            }
        }
        Map<String, String> signatures = new HashMap<>();
        first.forEach((name, sig) -> {
            int others = overloads.get(name) - 1;
            signatures.put(name, others > 0 ? sig + " +" + others : sig);
        });
        return signatures;
    }

    /** Schema hệ thống - index/sequence/trigger trong đó không phải thứ người dùng gõ tên. */
    private static final String PG_USER_SCHEMAS = "n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'";

    public static List<SchemaObject> loadIndexes(Connection conn) throws Exception {
        return loadObjects(conn, """
                    SELECT n.nspname, c.relname, t.relname
                    FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid
                    JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE %s ORDER BY 1, 2
                """.formatted(PG_USER_SCHEMAS), false);
    }

    public static List<SchemaObject> loadSequences(Connection conn) throws Exception {
        return loadObjects(conn, """
                    SELECT n.nspname, c.relname, NULL
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind = 'S' AND %s ORDER BY 1, 2
                """.formatted(PG_USER_SCHEMAS), false);
    }

    public static List<SchemaObject> loadTriggers(Connection conn) throws Exception {
        return loadObjects(conn, """
                    SELECT n.nspname, tg.tgname, c.relname
                    FROM pg_catalog.pg_trigger tg
                    JOIN pg_catalog.pg_class c ON c.oid = tg.tgrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE NOT tg.tgisinternal AND %s ORDER BY 1, 2
                """.formatted(PG_USER_SCHEMAS), false);
    }

    /** Khoá là cả tên trần lẫn "schema.tên": format_type() của cột chỉ ghi schema khi kiểu nằm ngoài search_path. */
    public static Map<String, List<String>> loadEnumValues(Connection conn) throws Exception {
        String sql = """
                    SELECT n.nspname, t.typname, e.enumlabel
                    FROM pg_catalog.pg_enum e
                    JOIN pg_catalog.pg_type t ON t.oid = e.enumtypid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                    ORDER BY t.oid, e.enumsortorder
                """;
        Map<String, List<String>> values = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString(2);
                String label = rs.getString(3);
                values.computeIfAbsent(type, k -> new ArrayList<>()).add(label);
                values.computeIfAbsent(rs.getString(1) + "." + type, k -> new ArrayList<>()).add(label);
            }
        }
        return values;
    }

    /** Cột 1-3: schema, tên, bảng (có thể NULL). {@code oracle}: tên catalog qua {@link #oracleName}. */
    private static List<SchemaObject> loadObjects(Connection conn, String sql, boolean oracle) throws Exception {
        List<SchemaObject> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString(1);
                String name = rs.getString(2);
                String table = rs.getString(3);
                out.add(oracle
                        ? new SchemaObject(oracleName(schema), oracleName(name), table == null ? null : oracleName(table))
                        : new SchemaObject(schema, name, table));
            }
        }
        return out;
    }

    public static List<SchemaObject> loadIndexesOracle(Connection conn) throws Exception {
        return loadObjects(conn, "SELECT owner, index_name, table_name FROM all_indexes WHERE "
                + oracleOwnerFilter(conn, "owner") + " ORDER BY 1, 2", true);
    }

    public static List<SchemaObject> loadSequencesOracle(Connection conn) throws Exception {
        return loadObjects(conn, "SELECT sequence_owner, sequence_name, NULL FROM all_sequences WHERE "
                + oracleOwnerFilter(conn, "sequence_owner") + " ORDER BY 1, 2", true);
    }

    public static List<SchemaObject> loadTriggersOracle(Connection conn) throws Exception {
        return loadObjects(conn, "SELECT owner, trigger_name, table_name FROM all_triggers WHERE "
                + oracleOwnerFilter(conn, "owner") + " ORDER BY 1, 2", true);
    }

    /** Khoá ngoại của mọi bảng, mỗi cặp cột (nguồn, đích) 1 dòng theo đúng thứ tự trong khoá. */
    public static List<ForeignKey> loadForeignKeys(Connection conn) throws Exception {
        String sql = """
                    SELECT c.oid,
                           sn.nspname AS from_schema, st.relname AS from_table, sa.attname AS from_column,
                           tn.nspname AS to_schema, tt.relname AS to_table, ta.attname AS to_column
                    FROM pg_catalog.pg_constraint c
                    JOIN pg_catalog.pg_class st ON st.oid = c.conrelid
                    JOIN pg_catalog.pg_namespace sn ON sn.oid = st.relnamespace
                    JOIN pg_catalog.pg_class tt ON tt.oid = c.confrelid
                    JOIN pg_catalog.pg_namespace tn ON tn.oid = tt.relnamespace
                    CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(from_attnum, to_attnum, ord)
                    JOIN pg_catalog.pg_attribute sa ON sa.attrelid = c.conrelid AND sa.attnum = k.from_attnum
                    JOIN pg_catalog.pg_attribute ta ON ta.attrelid = c.confrelid AND ta.attnum = k.to_attnum
                    WHERE c.contype = 'f'
                    ORDER BY c.oid, k.ord
                """;

        Map<Long, ForeignKeyBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String fromTable = rs.getString("from_schema") + "." + rs.getString("from_table");
                String toTable = rs.getString("to_schema") + "." + rs.getString("to_table");
                builders.computeIfAbsent(rs.getLong("oid"), k -> new ForeignKeyBuilder(fromTable, toTable))
                        .add(rs.getString("from_column"), rs.getString("to_column"));
            }
        }
        return builders.values().stream().map(ForeignKeyBuilder::build).toList();
    }

    /** search_path thực tế (đã bỏ schema không tồn tại, chưa gồm pg_catalog ngầm định), theo thứ tự ưu tiên. */
    public static List<String> loadSearchPath(Connection conn) throws Exception {
        List<String> path = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_catalog.current_schemas(false)");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                for (Object schema : (Object[]) rs.getArray(1).getArray()) {
                    path.add(schema.toString());
                }
            }
        }
        return path;
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

    public static List<String> loadTablespaces(Connection conn) throws Exception {
        String sql = "SELECT spcname FROM pg_catalog.pg_tablespace ORDER BY spcname";
        List<String> tablespaces = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tablespaces.add(rs.getString("spcname"));
            }
        }
        return tablespaces;
    }

    public static List<String> loadDataTypes(Connection conn) throws Exception {
        String sql = """
                    SELECT typname
                    FROM pg_catalog.pg_type
                    WHERE typtype IN ('b', 'd', 'e')   -- base type, domain, enum
                      AND typelem = 0                  -- bỏ array types (_int4, _text...)
                      AND typnamespace IN (
                          SELECT oid FROM pg_namespace
                          WHERE nspname = ANY (pg_catalog.current_schemas(true))
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

    /**
     * Điều kiện "cột owner thuộc schema người dùng thấy được, trừ schema hệ thống" cho các view ALL_*. Bảng của schema
     * khác (hr.employees...) rất hay được đọc trong Oracle; USER_* chỉ thấy schema đang đăng nhập. ORACLE_MAINTAINED
     * có từ 12c - DB cũ hơn thì chỉ lấy schema hiện tại như trước.
     */
    private static String oracleOwnerFilter(Connection conn, String ownerColumn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM all_users WHERE oracle_maintained = 'N'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return ownerColumn + " IN (SELECT username FROM all_users WHERE oracle_maintained = 'N')";
        } catch (Exception e) {
            return ownerColumn + " = USER";
        }
    }

    /**
     * Bảng/view/materialized view của mọi schema thấy được (xem {@link #oracleOwnerFilter}), cộng synonym trỏ tới
     * chúng: synonym riêng của user hiện tại vào schema hiện tại, synonym PUBLIC vào schema giả "public" (đứng sau
     * schema hiện tại trong search_path - đúng thứ tự Oracle tìm tên trần). Tên qua {@link #oracleName}.
     */
    public static List<SchemaInfo> loadSchemaOracle(Connection conn) throws Exception {
        String currentSchema = oracleName(currentOracleSchema(conn));
        String owners = oracleOwnerFilter(conn, "owner");

        Map<String, String> kindByTable = new LinkedHashMap<>(); // "owner.tên" -> loại
        for (String[] source : new String[][]{
                {"SELECT owner, table_name FROM all_tables WHERE " + owners, "table"},
                {"SELECT owner, view_name FROM all_views WHERE " + owners, "view"},
                {"SELECT owner, mview_name FROM all_mviews WHERE " + owners, "materialized view"}}) {
            try (PreparedStatement ps = conn.prepareStatement(source[0]);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kindByTable.put(oracleName(rs.getString(1)) + "." + oracleName(rs.getString(2)), source[1]);
                }
            }
        }

        String sql = """
                    SELECT owner, table_name, column_name, data_type, data_length, data_precision, data_scale, nullable
                    FROM all_tab_columns
                    WHERE %s
                    ORDER BY owner, table_name, column_id
                """.formatted(owners);

        Map<String, TableBuilder> builders = new LinkedHashMap<>(); // "owner.tên" -> bảng
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schemaName = oracleName(rs.getString("owner"));
                String tableName = oracleName(rs.getString("table_name"));
                String key = schemaName + "." + tableName;
                if (!kindByTable.containsKey(key)) {
                    continue; // cột của cluster, bảng trong recycle bin...
                }
                String colName = oracleName(rs.getString("column_name"));
                String dataType = formatOracleType(rs.getString("data_type"), rs.getInt("data_length"),
                        rs.getObject("data_precision") == null ? null : rs.getInt("data_precision"),
                        rs.getObject("data_scale") == null ? null : rs.getInt("data_scale"));
                boolean notNull = "N".equals(rs.getString("nullable"));

                builders.computeIfAbsent(key, k -> new TableBuilder(schemaName, tableName, kindByTable.get(key)))
                        .addColumn(new ColumnInfo(colName, tableName + "." + colName, dataType, notNull));
            }
        }

        // synonym: cùng cột với bảng đích; chỉ synonym trỏ tới bảng đã nạp (bỏ synonym PUBLIC của SYS... và db link)
        String synonyms = """
                    SELECT owner, synonym_name, table_owner, table_name
                    FROM all_synonyms
                    WHERE owner IN (USER, 'PUBLIC') AND db_link IS NULL AND %s
                """.formatted(oracleOwnerFilter(conn, "table_owner"));
        Map<String, TableBuilder> synonymBuilders = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(synonyms);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TableBuilder target = builders.get(oracleName(rs.getString("table_owner")) + "." + oracleName(rs.getString("table_name")));
                String schemaName = "PUBLIC".equals(rs.getString("owner")) ? "public" : currentSchema;
                String name = oracleName(rs.getString("synonym_name"));
                String key = schemaName + "." + name;
                if (target != null && !builders.containsKey(key)) {
                    TableBuilder synonym = new TableBuilder(schemaName, name, target.kind);
                    target.columns.forEach(synonym::addColumn);
                    synonymBuilders.putIfAbsent(key, synonym);
                }
            }
        } catch (Exception e) {
            // không đọc được synonym (quyền hạn chế...) thì vẫn dùng được bảng thật
        }
        builders.putAll(synonymBuilders);

        Map<String, List<TableInfo>> bySchema = new LinkedHashMap<>();
        bySchema.put(currentSchema, new ArrayList<>()); // schema hiện tại đứng đầu, kể cả khi chưa có bảng nào
        builders.values().forEach(b -> bySchema.computeIfAbsent(b.schema, k -> new ArrayList<>()).add(b.build()));
        return bySchema.entrySet().stream().map(e -> new SchemaInfo(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    /** Khoá ngoại giữa các bảng của mọi schema thấy được (cùng phạm vi loadSchemaOracle() nạp). */
    public static List<ForeignKey> loadForeignKeysOracle(Connection conn) throws Exception {
        String sql = """
                    SELECT c.owner, c.constraint_name, c.table_name, cc.column_name,
                           r.owner AS r_owner, r.table_name AS r_table_name, rc.column_name AS r_column_name
                    FROM all_constraints c
                    JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                    JOIN all_constraints r ON r.owner = c.r_owner AND r.constraint_name = c.r_constraint_name
                    JOIN all_cons_columns rc ON rc.owner = r.owner AND rc.constraint_name = r.constraint_name
                                            AND rc.position = cc.position
                    WHERE c.constraint_type = 'R' AND %s
                    ORDER BY c.owner, c.constraint_name, cc.position
                """.formatted(oracleOwnerFilter(conn, "c.owner"));

        Map<String, ForeignKeyBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String fromTable = oracleName(rs.getString("owner")) + "." + oracleName(rs.getString("table_name"));
                String toTable = oracleName(rs.getString("r_owner")) + "." + oracleName(rs.getString("r_table_name"));
                builders.computeIfAbsent(rs.getString("owner") + "." + rs.getString("constraint_name"),
                                k -> new ForeignKeyBuilder(fromTable, toTable))
                        .add(oracleName(rs.getString("column_name")), oracleName(rs.getString("r_column_name")));
            }
        }
        return builders.values().stream().map(ForeignKeyBuilder::build).toList();
    }

    /** Schema hiện tại, rồi synonym PUBLIC (schema giả "public" của loadSchemaOracle()) - thứ tự Oracle tìm tên trần. */
    public static List<String> loadSearchPathOracle(Connection conn) throws Exception {
        return List.of(oracleName(currentOracleSchema(conn)), "public");
    }

    /**
     * Tên Oracle trong catalog -> tên trong index: tên tạo không quote (lưu chữ HOA) hạ về chữ thường cho khớp kiểu gõ
     * thường; tên tạo có quote mà có chữ thường ("OrderItems") giữ nguyên - gõ trần thì Oracle không tìm ra, phải
     * quote đúng hoa/thường (xem {@link Identifiers#quote}); riêng tên toàn chữ thường ("abc") giữ cả ngoặc kép để không
     * trùng tên ABC đã hạ về thường.
     */
    static String oracleName(String raw) {
        if (raw.equals(raw.toUpperCase())) {
            return raw.toLowerCase();
        }
        // toàn chữ thường ("abc" tạo có quote): hạ về thường thì trùng với bảng ABC thường - giữ luôn ngoặc kép
        return raw.equals(raw.toLowerCase()) ? "\"" + raw + "\"" : raw;
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

    /** Chữ ký các hàm hay dùng nhất trong {@link #loadFunctionsOracle()} - Oracle không có catalog cho hàm built-in. */
    public static Map<String, String> functionSignaturesOracle() {
        return Map.ofEntries(
                Map.entry("nvl", "(expr, replacement)"),
                Map.entry("nvl2", "(expr, if_not_null, if_null)"),
                Map.entry("decode", "(expr, search, result [, ...] [, default])"),
                Map.entry("coalesce", "(expr, expr [, ...])"),
                Map.entry("nullif", "(expr1, expr2)"),
                Map.entry("substr", "(str, start [, length])"),
                Map.entry("instr", "(str, substr [, start [, occurrence]])"),
                Map.entry("length", "(str)"),
                Map.entry("replace", "(str, search [, replacement])"),
                Map.entry("lpad", "(str, length [, pad])"),
                Map.entry("rpad", "(str, length [, pad])"),
                Map.entry("to_char", "(value [, format [, nls]])"),
                Map.entry("to_date", "(str [, format [, nls]])"),
                Map.entry("to_number", "(str [, format [, nls]])"),
                Map.entry("to_timestamp", "(str [, format [, nls]])"),
                Map.entry("round", "(value [, places | format])"),
                Map.entry("trunc", "(value [, places | format])"),
                Map.entry("mod", "(n, m)"),
                Map.entry("add_months", "(date, n)"),
                Map.entry("months_between", "(date1, date2)"),
                Map.entry("last_day", "(date)"),
                Map.entry("next_day", "(date, weekday)"),
                Map.entry("extract", "(field FROM datetime)"),
                Map.entry("listagg", "(expr [, delimiter]) WITHIN GROUP (ORDER BY ...)"),
                Map.entry("regexp_like", "(str, pattern [, flags])"),
                Map.entry("regexp_substr", "(str, pattern [, start [, occurrence [, flags]]])"),
                Map.entry("regexp_replace", "(str, pattern [, replacement [, start [, occurrence [, flags]]]])"),
                Map.entry("lag", "(expr [, offset [, default]]) OVER (...)"),
                Map.entry("lead", "(expr [, offset [, default]]) OVER (...)"));
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

    private static class ForeignKeyBuilder {
        final String fromTable, toTable;
        final List<String> fromColumns = new ArrayList<>();
        final List<String> toColumns = new ArrayList<>();

        ForeignKeyBuilder(String fromTable, String toTable) {
            this.fromTable = fromTable;
            this.toTable = toTable;
        }

        ForeignKeyBuilder add(String fromColumn, String toColumn) {
            fromColumns.add(fromColumn);
            toColumns.add(toColumn);
            return this;
        }

        ForeignKey build() {
            return new ForeignKey(fromTable, List.copyOf(fromColumns), toTable, List.copyOf(toColumns));
        }
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