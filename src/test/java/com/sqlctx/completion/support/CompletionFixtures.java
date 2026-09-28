package com.sqlctx.completion.support;

import com.sqlctx.schema.ColumnInfo;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import com.sqlctx.schema.SchemaInfo;
import com.sqlctx.schema.TableInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema giả lập DUY NHẤT cho mọi test completion - thay cho DB thật (không cần Docker).
 * <p>
 * Gán LẠI TOÀN BỘ field static của {@link SchemaIndex} mỗi lần gọi (kể cả roles/languages/dialect)
 * - bug thật đã gặp: fixture Oracle cũ không đụng tới roles/languages nên kết quả test Oracle phụ
 * thuộc vào việc test Postgres có chạy trước trong cùng JVM hay không.
 * <p>
 * Tập cột/bảng kỳ vọng trong các test Postgres đã được đối chiếu 1 lần với Postgres 18 thật tạo
 * đúng schema này (thay từng ứng viên vào vị trí con trỏ rồi chạy câu lệnh, xem lỗi 42703/42P01/
 * 42809...) - test KHÔNG cần DB, chỉ dùng fixture này.
 */
public final class CompletionFixtures {

    public static final List<String> PG_ROLES = List.of("app_reader", "app_writer", "postgres");
    public static final List<String> PG_FUNCTIONS = List.of("count", "sum", "avg", "now");
    public static final List<String> PG_DATATYPES = List.of("int4", "text", "numeric", "bool", "timestamp");
    public static final List<String> PG_LANGUAGES = List.of("plpgsql", "sql");
    public static final List<String> PG_TABLES = List.of("public.users", "public.orders", "public.contracts", "public.products");
    public static final List<String> PG_VIEWS = List.of("public.active_users");
    public static final List<String> PG_MATVIEWS = List.of("public.daily_totals");
    /** Mỗi bảng/view/materialized view cũng là 1 kiểu composite cùng tên (RETURNS users, CAST(x AS users)...). */
    public static final List<String> PG_COMPOSITE_TYPES = List.of("public.users", "public.orders", "public.contracts",
            "public.products", "public.active_users", "public.daily_totals");
    /** Mọi tên kiểu hợp lệ ở vị trí khai báo kiểu: kiểu cơ bản + kiểu composite. */
    public static final List<String> PG_TYPE_NAMES = java.util.stream.Stream.concat(
            PG_DATATYPES.stream(), PG_COMPOSITE_TYPES.stream()).toList();

    public static final List<String> ORA_FUNCTIONS = List.of("count", "sum", "avg", "sysdate");
    public static final List<String> ORA_DATATYPES = List.of("NUMBER", "VARCHAR2", "DATE", "CHAR");
    public static final List<String> ORA_TABLES = List.of("naviq.users", "naviq.orders", "naviq.contracts", "naviq.products");

    private CompletionFixtures() {
    }

    public static void installPostgres() {
        var id = col("id", "int4", true);
        var name = col("name", "text");
        var email = col("email", "text");
        var customerId = col("customer_id", "int4");
        var total = col("total", "numeric");
        var status = col("status", "text");
        var userId = col("user_id", "int4");
        var amount = col("amount", "numeric");
        var price = col("price", "numeric");
        var quantity = col("quantity", "int4");
        var description = col("description", "text");

        var tables = List.of(
                new TableInfo("public", "users", "table", List.of(id, name, email)),
                new TableInfo("public", "orders", "table", List.of(id, customerId, total, status, userId)),
                new TableInfo("public", "contracts", "table", List.of(id, name, amount, status)),
                new TableInfo("public", "products", "table", List.of(id, name, price, quantity, description)),
                new TableInfo("public", "active_users", "view", List.of(id, name)),
                // Materialized view - cần để test được việc lọc đúng loại relation theo lệnh
                // (REFRESH MATERIALIZED VIEW, REINDEX, CREATE INDEX ON... chấp nhận; DROP TABLE,
                // TRUNCATE, INSERT... không).
                new TableInfo("public", "daily_totals", "materialized view", List.of(id, total)));
        install(Dialect.POSTGRES, "public", tables);
        SchemaIndex.functions = PG_FUNCTIONS;
        SchemaIndex.dataTypes = PG_DATATYPES;
        SchemaIndex.roles = PG_ROLES;
        SchemaIndex.languages = PG_LANGUAGES;
    }

    public static void installOracle() {
        var id = col("id", "NUMBER", true);
        var name = col("name", "VARCHAR2");
        var email = col("email", "VARCHAR2");
        var customerId = col("customer_id", "NUMBER");
        var total = col("total", "NUMBER");
        var status = col("status", "VARCHAR2");
        var userId = col("user_id", "NUMBER");
        var amount = col("amount", "NUMBER");
        var price = col("price", "NUMBER");
        var quantity = col("quantity", "NUMBER");
        var description = col("description", "VARCHAR2");

        var tables = List.of(
                new TableInfo("naviq", "users", "table", List.of(id, name, email)),
                new TableInfo("naviq", "orders", "table", List.of(id, customerId, total, status, userId)),
                new TableInfo("naviq", "contracts", "table", List.of(id, name, amount, status)),
                new TableInfo("naviq", "products", "table", List.of(id, name, price, quantity, description)));
        install(Dialect.ORACLE, "naviq", tables);
        SchemaIndex.functions = ORA_FUNCTIONS;
        SchemaIndex.dataTypes = ORA_DATATYPES;
        // Oracle không model role/ngôn ngữ thủ tục - rỗng TƯỜNG MINH, không để sót từ test Postgres.
        SchemaIndex.roles = List.of();
        SchemaIndex.languages = List.of();
    }

    private static void install(Dialect dialect, String schema, List<TableInfo> tables) {
        Map<String, TableInfo> tableIndex = new LinkedHashMap<>();
        Map<String, TableInfo> schemaTableIndex = new LinkedHashMap<>();
        for (TableInfo t : tables) {
            tableIndex.put(t.fullName(), t);
            tableIndex.put(t.name(), t);
            schemaTableIndex.put(t.fullName(), t);
        }
        SchemaIndex.dialect = dialect;
        SchemaIndex.schemas = List.of(new SchemaInfo(schema, tables));
        SchemaIndex.tableIndex = tableIndex;
        SchemaIndex.schemaTableIndex = schemaTableIndex;
    }

    private static ColumnInfo col(String name, String type) {
        return col(name, type, false);
    }

    private static ColumnInfo col(String name, String type, boolean notNull) {
        return new ColumnInfo(name, name, type, notNull);
    }
}
