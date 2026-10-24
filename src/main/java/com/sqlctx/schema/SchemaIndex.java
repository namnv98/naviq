package com.sqlctx.schema;

import com.sqlctx.dialect.DialectAdapter;
import com.sqlctx.dialect.DialectAdapters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Load + index schema từ DB thật. Tách riêng khỏi PostgresCompletionEngine vì đây
 * là 1 trách nhiệm hoàn toàn độc lập ("biết schema có gì") - không liên quan gì tới
 * việc parse/resolve alias của ScopeBuilder hay dự đoán token của
 * AntlrCompletionEngineFix.
 * <p>
 * Không tự nạp trong static initializer: code chạy thật gọi {@link #reload(Dialect)} tường minh lúc khởi động/đổi
 * context (lỗi kết nối hiện rõ ở đó), còn test gán thẳng field bằng fixture mà không cần DB. Static init cũ từng
 * nạp schema 2 lần mỗi lần khởi động (lần đầu luôn theo Postgres, Oracle thì hỏng) và in stack trace trong test.
 */
public class SchemaIndex {

    public static volatile List<SchemaInfo> schemas = List.of();
    public static volatile Map<String, TableInfo> tableIndex = Map.of();
    public static volatile Map<String, TableInfo> schemaTableIndex = Map.of();
    public static volatile List<String> functions = List.of();
    public static volatile List<String> dataTypes = List.of();
    /** Role/user thật của server (pg_roles) - dùng gợi ý ở GRANT/REVOKE/ALTER ROLE/OWNER TO... (rỗng ở Oracle). */
    public static volatile List<String> roles = List.of();
    /** Ngôn ngữ thủ tục thật đã cài (pg_language) - dùng gợi ý ở DO/CREATE FUNCTION ... LANGUAGE |. */
    public static volatile List<String> languages = List.of();
    /** Tablespace thật (pg_tablespace) - dùng gợi ý ở CREATE/ALTER ... TABLESPACE | (rỗng ở Oracle). */
    public static volatile List<String> tablespaces = List.of();
    /** Khoá ngoại thật - dùng gợi ý điều kiện JOIN ... ON. */
    public static volatile List<ForeignKey> foreignKeys = List.of();
    /** Schema tìm tên trần, ưu tiên từ đầu (search_path của Postgres, schema hiện tại của Oracle). Rỗng = không biết: mọi bảng gợi ý dạng "schema.bảng" như trước. */
    public static volatile List<String> searchPath = List.of();
    public static volatile List<SchemaObject> indexes = List.of();
    public static volatile List<SchemaObject> sequences = List.of();
    public static volatile List<SchemaObject> triggers = List.of();
    /** Kiểu enum ("tên" và "schema.tên", như format_type trả về cho cột) -> giá trị theo đúng thứ tự khai báo. */
    public static volatile Map<String, List<String>> enumValues = Map.of();
    /** Tên hàm chữ thường -> chữ ký hiển thị cạnh gợi ý; thiếu tên nào thì gợi ý tên đó không kèm chữ ký. */
    public static volatile Map<String, String> functionSignatures = Map.of();


    public static volatile Dialect dialect = Dialect.POSTGRES;

    public static void reload() {
        reload(Dialect.POSTGRES);
    }

    /** Tăng mỗi lần bắt đầu nạp; lần nạp nào xong mà đã có lần mới hơn bắt đầu thì bỏ kết quả (vd \ctx giữa chừng). */
    private static final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    /** Nạp đồng bộ - lỗi kết nối/quyền ném ra cho caller. */
    public static void reload(Dialect d) {
        dialect = d;
        load(d, generation.incrementAndGet());
    }

    /**
     * Nạp ở thread nền (lúc khởi động REPL: schema lớn không chặn ô nhập). Gợi ý dùng schema cũ/rỗng tới khi xong.
     *
     * @param done gọi khi xong (null = thành công, khác null = lỗi); không gọi nếu kết quả bị bỏ vì có lần nạp mới hơn
     */
    public static void reloadInBackground(Dialect d, java.util.function.Consumer<Throwable> done) {
        dialect = d;
        long gen = generation.incrementAndGet();
        Thread t = new Thread(() -> {
            Throwable error = null;
            try {
                if (!load(d, gen)) {
                    return;
                }
            } catch (Throwable e) {
                error = e;
            }
            done.accept(error);
        }, "schema-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Tính hết vào biến cục bộ rồi mới gán một lượt: gợi ý chạy song song không thấy schema nửa cũ nửa mới. */
    private static boolean load(Dialect d, long gen) {
        try {
            DialectAdapter adapter = DialectAdapters.of(d);
            var newSchemas = adapter.loadSchema();
            var newDataTypes = adapter.loadDataTypes();
            var newFunctions = adapter.loadFunctions();
            var newRoles = adapter.loadRoles();
            var newLanguages = adapter.loadLanguages();
            var newTablespaces = adapter.loadTablespaces();
            var newForeignKeys = adapter.loadForeignKeys();
            var newSearchPath = adapter.loadSearchPath();
            var newSignatures = adapter.loadFunctionSignatures();
            var newIndexes = adapter.loadIndexes();
            var newSequences = adapter.loadSequences();
            var newTriggers = adapter.loadTriggers();
            var newEnumValues = adapter.loadEnumValues();
            var newTableIndex = buildIndex(newSchemas, newSearchPath);
            var newSchemaTableIndex = buildSchemaTableIndex(newSchemas);
            synchronized (SchemaIndex.class) {
                if (gen != generation.get()) {
                    return false;
                }
                schemas = newSchemas;
                dataTypes = newDataTypes;
                functions = newFunctions;
                roles = newRoles;
                languages = newLanguages;
                tablespaces = newTablespaces;
                foreignKeys = newForeignKeys;
                searchPath = newSearchPath;
                functionSignatures = newSignatures;
                indexes = newIndexes;
                sequences = newSequences;
                triggers = newTriggers;
                enumValues = newEnumValues;
                tableIndex = newTableIndex;
                schemaTableIndex = newSchemaTableIndex;
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * "schema.bảng" và tên trần -> bảng. Tên trần trùng ở nhiều schema thì lấy bảng mà DB thật sẽ chọn: schema đứng
     * trước trong search_path; không schema nào trong search_path có tên đó thì lấy bảng gặp đầu tiên.
     */
    public static Map<String, TableInfo> buildIndex(List<SchemaInfo> schemas, List<String> searchPath) {
        Map<String, TableInfo> index = new HashMap<>();
        for (SchemaInfo s : schemas) {
            for (TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
                index.putIfAbsent(t.name(), t);
            }
        }
        for (int i = searchPath.size() - 1; i >= 0; i--) {
            String schema = searchPath.get(i);
            for (SchemaInfo s : schemas) {
                if (s.name().equals(schema)) {
                    s.tables().forEach(t -> index.put(t.name(), t));
                }
            }
        }
        return index;
    }

    /**
     * Tên chèn vào câu SQL cho 1 bảng: tên trần nếu gõ tên trần thì DB thật cũng tìm ra đúng bảng này (schema của nó
     * nằm trong search_path và không bị bảng cùng tên ở schema đứng trước che), ngược lại "schema.bảng".
     */
    public static String displayName(TableInfo t) {
        return searchPath.contains(t.schema()) && tableIndex.get(t.name()) == t
                ? Identifiers.quote(t.name(), dialect)
                : qualifiedName(t);
    }

    /** Schema hệ thống của Postgres - bảng trong đó hiếm khi là thứ người dùng định gõ. */
    private static final java.util.Set<String> SYSTEM_SCHEMAS = java.util.Set.of("pg_catalog", "information_schema", "pg_toast");

    public static boolean isSystemSchema(String schema) {
        return SYSTEM_SCHEMAS.contains(schema);
    }

    /** Tên chèn vào câu cho index/sequence: tên trần nếu schema nằm trong search_path, ngược lại "schema.tên". */
    public static String displayName(SchemaObject o) {
        return searchPath.contains(o.schema())
                ? Identifiers.quote(o.name(), dialect)
                : Identifiers.quote(o.schema(), dialect) + "." + Identifiers.quote(o.name(), dialect);
    }

    /** "schema.bảng" chèn được vào câu SQL (từng phần đặt trong ngoặc kép nếu cần). */
    public static String qualifiedName(TableInfo t) {
        return Identifiers.quote(t.schema(), dialect) + "." + Identifiers.quote(t.name(), dialect);
    }

    /** Tên cột chèn được vào câu SQL. */
    public static String columnName(ColumnInfo c) {
        return Identifiers.quote(c.name(), dialect);
    }

    /**
     * Bảng theo tên như xuất hiện trong câu ("users", "PUBLIC.Users", "\"OrderItems\""...). Thử đúng nguyên văn trước
     * (tầng ngữ nghĩa Postgres đã bỏ ngoặc kép, chỉ còn đúng hoa/thường) rồi mới tới dạng đã chuẩn hoá theo DB.
     */
    public static TableInfo findTable(String name) {
        if (name == null) {
            return null;
        }
        TableInfo exact = tableIndex.get(name);
        return exact != null ? exact : tableIndex.get(Identifiers.normalizeQualified(name, dialect));
    }

    private static Map<String, TableInfo> buildSchemaTableIndex(List<SchemaInfo> schemas) {
        Map<String, TableInfo> index = new HashMap<>();
        for (SchemaInfo s : schemas) {
            for (TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
            }
        }
        return index;
    }

    public static List<ColumnInfo> getColumnsOfTable(String tableName) {
        TableInfo t = findTable(tableName);
        if (t == null) return List.of();
        return t.columns().stream().toList();
    }

    public static List<TableInfo> getTablesBySchema(String schemaName) {
        String normalized = Identifiers.normalize(schemaName, dialect);
        return schemas.stream()
                .filter(s -> s.name().equals(schemaName) || s.name().equals(normalized))
                .findFirst()
                .map(s -> s.tables().stream().toList())
                .orElse(List.of());
    }
}