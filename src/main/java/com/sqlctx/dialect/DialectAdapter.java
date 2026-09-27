package com.sqlctx.dialect;

import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaInfo;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.sql.Connection;
import java.util.List;
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
