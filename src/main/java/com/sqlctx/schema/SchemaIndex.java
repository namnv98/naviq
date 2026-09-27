package com.sqlctx.schema;

import com.sqlctx.datasource.OracleDataSource;
import com.sqlctx.datasource.PostgresDataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load + index schema từ DB thật. Tách riêng khỏi PostgresCompletionEngine vì đây
 * là 1 trách nhiệm hoàn toàn độc lập ("biết schema có gì") - không liên quan gì tới
 * việc parse/resolve alias của ScopeBuilder hay dự đoán token của
 * AntlrCompletionEngineFix.
 * <p>
 * CẬP NHẬT (testability): static initializer TRƯỚC ĐÂY gọi thẳng reload() và cho ném
 * RuntimeException nếu thiếu cấu hình DB (system property DB_HOST/...) - nghĩa là chỉ
 * CHẠM vào class này (kể cả để override field bằng fixture trong test) là ĐàCRASH ngay
 * (ExceptionInInitializerError, "sticky" cho cả JVM đang chạy, set fixture sau đó cũng
 * không cứu được vì lỗi xảy ra TRƯỚC khi bất kỳ dòng test nào kịp chạy). Giờ static init
 * CHỈ log cảnh báo + để field mặc định RỖNG nếu reload() thất bại - code thật (CLI khi
 * chạy production) vẫn nên tự gọi reload() TƯỜNG MINH lúc khởi động (ném lỗi to, rõ ràng
 * đúng như cũ nếu thật sự thiếu cấu hình) - còn test có thể an toàn override field bằng
 * fixture mà không cần DB thật.
 */
public class SchemaIndex {

    private static final Logger LOG = Logger.getLogger(SchemaIndex.class.getName());

    public static volatile List<SchemaInfo> schemas = List.of();
    public static volatile Map<String, TableInfo> tableIndex = Map.of();
    public static volatile Map<String, TableInfo> schemaTableIndex = Map.of();
    public static volatile List<String> functions = List.of();
    public static volatile List<String> dataTypes = List.of();

    static {
        try {
            reload();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Không load được schema từ DB lúc khởi tạo class (sẽ dùng schema RỖNG cho tới " + "khi reload() được gọi lại thành công) - " + e.getMessage(), e);
        }
    }

    public static volatile Dialect dialect = Dialect.POSTGRES;

    public static void reload() {
        reload(Dialect.POSTGRES);
    }

    public static void reload(Dialect d) {
        dialect = d;
        try {
            if (d == Dialect.ORACLE) {
                var conn = OracleDataSource.get();
                schemas = SchemaLoader.loadSchemaOracle(conn);
                dataTypes = SchemaLoader.loadDataTypesOracle();
                functions = SchemaLoader.loadFunctionsOracle();
            } else {
                var conn = PostgresDataSource.get();
                schemas = SchemaLoader.loadSchema(conn);
                dataTypes = SchemaLoader.loadDataTypes(conn);
                functions = SchemaLoader.loadFunctions(conn);
            }
            tableIndex = buildIndex(schemas);
            schemaTableIndex = buildSchemaTableIndex(schemas);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, TableInfo> buildIndex(List<SchemaInfo> schemas) {
        Map<String, TableInfo> index = new HashMap<>();
        for (SchemaInfo s : schemas) {
            for (TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
                index.put(t.name(), t);
            }
        }
        return index;
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
        TableInfo t = tableIndex.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream().toList();
    }

    public static List<TableInfo> getTablesBySchema(String schemaName) {
        return schemas.stream()
                .filter(s -> s.name().equals(schemaName))
                .findFirst()
                .map(s -> s.tables().stream().toList())
                .orElse(List.of());
    }
}