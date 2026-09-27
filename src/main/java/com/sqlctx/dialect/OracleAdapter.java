package com.sqlctx.dialect;

import com.sqlctx.antlr4.oracle.PlSqlLexer;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.oracle.OracleSuggestionService;
import com.sqlctx.datasource.OracleDataSource;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaInfo;
import com.sqlctx.schema.SchemaLoader;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OracleAdapter implements DialectAdapter {

    public static final OracleAdapter INSTANCE = new OracleAdapter();

    private OracleAdapter() {
    }

    @Override
    public Dialect id() {
        return Dialect.ORACLE;
    }

    @Override
    public Connection connection() {
        return OracleDataSource.get();
    }

    @Override
    public void reconnect(String dbOrService) throws Exception {
        OracleDataSource.reconnect(dbOrService);
    }

    @Override
    public void close() throws Exception {
        OracleDataSource.close();
    }

    @Override
    public List<SchemaInfo> loadSchema() throws Exception {
        return SchemaLoader.loadSchemaOracle(connection());
    }

    @Override
    public List<String> loadFunctions() throws Exception {
        return SchemaLoader.loadFunctionsOracle();
    }

    @Override
    public List<String> loadDataTypes() throws Exception {
        return SchemaLoader.loadDataTypesOracle();
    }

    @Override
    public DatabaseList listDatabases() throws Exception {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM v$pdbs ORDER BY name")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return new DatabaseList(null, names);
        } catch (Exception e) {
            // Không phải connect tới CDB root (không thấy v$pdbs) - user thường chỉ thấy PDB của chính
            // mình, liệt kê schema/user khác trong cùng PDB thay thế, đó là khái niệm gần nhất với
            // "database" ở đây.
            try (Statement stmt = connection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT username FROM all_users ORDER BY username")) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                return new DatabaseList("(không thấy PDB nào - đây là danh sách schema/user trong PDB hiện tại)\n", names);
            }
        }
    }

    @Override
    public TableResult describeUsers() throws Exception {
        // ALL_USERS không có cột account_status (đó là DBA_USERS, cần quyền DBA mà user thường không
        // có) - chỉ dùng username + created, cả hai đều có sẵn cho MỌI user không cần quyền đặc biệt gì thêm.
        List<String> headers = List.of("User", "Created");
        List<List<String>> rows = new ArrayList<>();
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT username, created FROM all_users ORDER BY username")) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), rs.getString(2)));
            }
        }
        return new TableResult(headers, rows);
    }

    @Override
    public Set<String> primaryKeyColumns(String tableName) throws Exception {
        String sql = "SELECT ucc.column_name FROM user_constraints uc "
                + "JOIN user_cons_columns ucc ON uc.constraint_name = ucc.constraint_name "
                + "WHERE uc.constraint_type = 'P' AND uc.table_name = UPPER(?)";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> pk = new HashSet<>();
                while (rs.next()) {
                    pk.add(rs.getString(1).toLowerCase());
                }
                return pk;
            }
        }
    }

    @Override
    public String serverVersion() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT banner FROM v$version WHERE ROWNUM = 1")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "Oracle (không đọc được version: " + e.getMessage() + ")";
        }
    }

    @Override
    public String serverTime() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS') FROM DUAL")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String serverTimezone() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT SESSIONTIMEZONE FROM DUAL")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String encoding() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String driverInfo() {
        try {
            var meta = connection().getMetaData();
            return meta.getDriverName() + " " + meta.getDriverVersion();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String prepareStatementText(String sql) {
        // Oracle (ojdbc) không chấp nhận dấu ';' cuối câu - đó là quy ước của SQL*Plus/CLI, không phải
        // cú pháp SQL thật.
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    @Override
    public Lexer createLexer(CharStream input) {
        return new PlSqlLexer(input);
    }

    @Override
    public boolean isIdentifierToken(int tokenType) {
        return tokenType == PlSqlLexer.REGULAR_ID;
    }

    @Override
    public List<Suggestion> suggest(CompletionInputPreparer.PrepareCompletionInput input) {
        return OracleSuggestionService.suggests(input);
    }
}
