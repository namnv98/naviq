package com.sqlctx.dialect;

import com.sqlctx.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.completion.model.Suggestion;
import com.sqlctx.completion.suggestion.CompletionInputPreparer;
import com.sqlctx.completion.suggestion.postgresql.PostgresSuggestionService;
import com.sqlctx.datasource.PostgresDataSource;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaInfo;
import com.sqlctx.schema.SchemaLoader;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PostgresAdapter implements DialectAdapter {

    public static final PostgresAdapter INSTANCE = new PostgresAdapter();

    private PostgresAdapter() {
    }

    @Override
    public Dialect id() {
        return Dialect.POSTGRES;
    }

    @Override
    public Connection connection() {
        return PostgresDataSource.get();
    }

    @Override
    public void reconnect(String dbOrService) throws Exception {
        PostgresDataSource.reconnect(dbOrService);
    }

    @Override
    public void close() throws Exception {
        PostgresDataSource.close();
    }

    @Override
    public List<SchemaInfo> loadSchema() throws Exception {
        return SchemaLoader.loadSchema(connection());
    }

    @Override
    public List<String> loadFunctions() throws Exception {
        return SchemaLoader.loadFunctions(connection());
    }

    @Override
    public List<String> loadDataTypes() throws Exception {
        return SchemaLoader.loadDataTypes(connection());
    }

    @Override
    public DatabaseList listDatabases() throws Exception {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return new DatabaseList(null, names);
        }
    }

    @Override
    public TableResult describeUsers() throws Exception {
        List<String> headers = List.of("Role", "Superuser", "Can login");
        List<List<String>> rows = new ArrayList<>();
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT rolname, rolsuper, rolcanlogin FROM pg_roles ORDER BY rolname")) {
            while (rs.next()) {
                rows.add(List.of(rs.getString(1), rs.getBoolean(2) ? "yes" : "", rs.getBoolean(3) ? "yes" : ""));
            }
        }
        return new TableResult(headers, rows);
    }

    @Override
    public Set<String> primaryKeyColumns(String tableName) throws Exception {
        String sql = "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = ?::regclass AND i.indisprimary";
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
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "PostgreSQL (không đọc được version: " + e.getMessage() + ")";
        }
    }

    @Override
    public String serverTime() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT NOW()")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String serverTimezone() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SHOW TIMEZONE")) {
            return rs.next() ? rs.getString(1) : "(unknown)";
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    @Override
    public String encoding() {
        try (Statement stmt = connection().createStatement();
             ResultSet rs = stmt.executeQuery("SHOW SERVER_ENCODING")) {
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
        // pgjdbc chấp nhận ';' cuối câu bình thường - không cần cắt.
        return sql;
    }

    @Override
    public Lexer createLexer(CharStream input) {
        return new PostgreSQLLexer(input);
    }

    @Override
    public boolean isIdentifierToken(int tokenType) {
        return tokenType == PostgreSQLLexer.Identifier || tokenType == PostgreSQLLexer.QuotedIdentifier;
    }

    @Override
    public List<Suggestion> suggest(CompletionInputPreparer.PrepareCompletionInput input) {
        return PostgresSuggestionService.suggests(input);
    }
}
