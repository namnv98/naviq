package com.naviq.datasource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load + index schema từ DB thật. Tách riêng khỏi PostgresCompletionEngine vì đây
 * là 1 trách nhiệm hoàn toàn độc lập ("biết schema có gì") - không liên quan gì tới
 * việc parse/resolve alias của SemanticScope hay dự đoán token của
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

    public static volatile List<SchemaLoader.SchemaInfo> DB_SCHEMA = List.of();
    public static volatile Map<String, SchemaLoader.TableInfo> TABLE_INDEX = Map.of();
    public static volatile Map<String, SchemaLoader.TableInfo> SCHEMA_TABLE_INDEX = Map.of();
    public static volatile List<String> FUNCTIONS = List.of();
    public static volatile List<String> DATA_TYPES = List.of();

    static {
        try {
            reload();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Không load được schema từ DB lúc khởi tạo class (sẽ dùng schema RỖNG cho tới " + "khi reload() được gọi lại thành công) - " + e.getMessage(), e);
        }
    }

    public static void reload() {
        try {
            DB_SCHEMA = SchemaLoader.loadSchema(PostgresDataSource.get());
            TABLE_INDEX = buildIndex(DB_SCHEMA);
            DATA_TYPES = SchemaLoader.loadDataTypes(PostgresDataSource.get());
            SCHEMA_TABLE_INDEX = buildSchemaTableIndex(DB_SCHEMA);
            FUNCTIONS = SchemaLoader.loadFunctions(PostgresDataSource.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, SchemaLoader.TableInfo> buildIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
                index.put(t.name(), t);
            }
        }
        return index;
    }

    private static Map<String, SchemaLoader.TableInfo> buildSchemaTableIndex(List<SchemaLoader.SchemaInfo> schemas) {
        Map<String, SchemaLoader.TableInfo> index = new HashMap<>();
        for (SchemaLoader.SchemaInfo s : schemas) {
            for (SchemaLoader.TableInfo t : s.tables()) {
                index.put(t.fullName(), t);
            }
        }
        return index;
    }

    public static List<SchemaLoader.DBColumnInfo> getColumnsOfTable(String tableName) {
        SchemaLoader.TableInfo t = TABLE_INDEX.get(tableName);
        if (t == null) return List.of();
        return t.columns().stream().toList();
    }

    public static List<SchemaLoader.TableInfo> getTablesBySchema(String schemaName) {
        return DB_SCHEMA.stream()
                .filter(s -> s.name().equals(schemaName))
                .findFirst()
                .map(s -> s.tables().stream().toList())
                .orElse(List.of());
    }
}