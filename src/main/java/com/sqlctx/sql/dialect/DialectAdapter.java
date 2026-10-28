package com.sqlctx.sql.dialect;

import com.sqlctx.sql.completion.model.Suggestion;
import com.sqlctx.sql.completion.input.CompletionInputPreparer;
import com.sqlctx.sql.schema.Dialect;
import com.sqlctx.sql.schema.ForeignKey;
import com.sqlctx.sql.schema.SchemaInfo;
import com.sqlctx.sql.schema.SchemaObject;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mọi hành vi PHỤ THUỘC DIALECT của toàn bộ app (kết nối, tải schema, câu SQL cho \l/\du/\d, cách tách
 * statement, lexer tô màu, engine gợi ý...) gom về 1 chỗ - thay vì rải rác "if (dialect == ORACLE) {...}
 * else {...}" khắp cli/schema/terminal như trước. Thêm 1 dialect mới (MySQL, SQL Server...) chỉ cần viết
 * 1 class implement interface này + đăng ký ở {@link DialectAdapters}, KHÔNG phải sửa lại các file đã
 * có (SchemaCommands, StatementExecutor, StartupBanner, SqlHighlighter, MenuCompleter...).
 * <p>
 * Mỗi implementation là 1 singleton KHÔNG GIỮ STATE riêng (state kết nối thật vẫn nằm ở
 * PostgresDataSource/OracleDataSource - 2 class đó vẫn giữ nguyên, adapter chỉ là lớp bọc mỏng phía
 * trước) - an toàn khi gọi qua {@link DialectAdapters#of(Dialect)} nhiều lần mà không cần cache.
 */
public interface DialectAdapter {

    Dialect id();

    Connection connection();
    void reconnect(String dbOrService) throws Exception;
    void close() throws Exception;

    List<SchemaInfo> loadSchema() throws Exception;
    List<String> loadFunctions() throws Exception;
    List<String> loadDataTypes() throws Exception;
    List<String> loadRoles() throws Exception;
    List<String> loadLanguages() throws Exception;
    List<String> loadTablespaces() throws Exception;

    /** Khoá ngoại - dùng gợi ý điều kiện {@code JOIN ... ON}. */
    default List<ForeignKey> loadForeignKeys() throws Exception {
        return List.of();
    }

    /** Schema được tìm khi gõ tên trần, theo thứ tự ưu tiên (search_path của Postgres, schema hiện tại của Oracle). */
    default List<String> loadSearchPath() throws Exception {
        return List.of();
    }

    default List<SchemaObject> loadIndexes() throws Exception {
        return List.of();
    }

    default List<SchemaObject> loadSequences() throws Exception {
        return List.of();
    }

    default List<SchemaObject> loadTriggers() throws Exception {
        return List.of();
    }

    /** Kiểu enum -> giá trị; khoá gồm cả tên trần lẫn "schema.tên" (format_type dùng dạng nào tuỳ search_path). */
    default Map<String, List<String>> loadEnumValues() throws Exception {
        return Map.of();
    }

    /** Tên hàm (chữ thường) -> chữ ký hiển thị cạnh gợi ý, vd "(text, timestamp) → timestamp". */
    default Map<String, String> loadFunctionSignatures() throws Exception {
        return Map.of();
    }

    /** note != null: dòng giải thích thêm (Oracle không thấy PDB thì liệt kê schema/user thay thế). */
    record DatabaseList(String note, List<String> names) {
    }

    DatabaseList listDatabases() throws Exception;

    record TableResult(List<String> headers, List<List<String>> rows) {
    }

    TableResult describeUsers() throws Exception;

    Set<String> primaryKeyColumns(String tableName) throws Exception;

    String serverVersion();
    String serverTime();
    String serverTimezone();
    String encoding();
    String driverInfo();

    /** Sửa lại text câu lệnh TRƯỚC khi gửi cho driver (vd Oracle không chấp nhận ';' cuối câu). */
    String prepareStatementText(String sql);

    Lexer createLexer(CharStream input);
    boolean isIdentifierToken(int tokenType);

    List<Suggestion> suggest(CompletionInputPreparer.PrepareCompletionInput input);
}
