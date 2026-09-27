package com.sqlctx.dialect;

import com.sqlctx.antlr4.postgresql.PostgreSQLLexer;
import com.sqlctx.antlr4.oracle.PlSqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prepareStatementText()/isIdentifierToken() là logic thuần (không cần kết nối DB thật) - test trực
 * tiếp không qua CLI.
 */
class DialectAdapterTest {

    @Test
    void postgresGiữNguyênDấuChấmPhẩyCuốiCâu() {
        assertEquals("select 1;", PostgresAdapter.INSTANCE.prepareStatementText("select 1;"));
    }

    @Test
    void postgresKhôngCóDấuChấmPhẩyCuốiCâuThìGiữNguyên() {
        assertEquals("select 1", PostgresAdapter.INSTANCE.prepareStatementText("select 1"));
    }

    @Test
    void oracleCắtDấuChấmPhẩyCuốiCâu() {
        assertEquals("select 1 from dual",
                OracleAdapter.INSTANCE.prepareStatementText("select 1 from dual;"));
    }

    @Test
    void oracleKhôngCóDấuChấmPhẩyCuốiCâuThìGiữNguyên() {
        assertEquals("select 1 from dual",
                OracleAdapter.INSTANCE.prepareStatementText("select 1 from dual"));
    }

    @Test
    void postgresNhậnDiệnTokenIdentifierVàQuotedIdentifier() {
        assertTrue(PostgresAdapter.INSTANCE.isIdentifierToken(PostgreSQLLexer.Identifier));
        assertTrue(PostgresAdapter.INSTANCE.isIdentifierToken(PostgreSQLLexer.QuotedIdentifier));
        assertFalse(PostgresAdapter.INSTANCE.isIdentifierToken(PostgreSQLLexer.SELECT));
    }

    @Test
    void oracleNhậnDiệnTokenRegularId() {
        assertTrue(OracleAdapter.INSTANCE.isIdentifierToken(PlSqlLexer.REGULAR_ID));
        assertFalse(OracleAdapter.INSTANCE.isIdentifierToken(PlSqlLexer.SELECT));
    }

    @Test
    void postgresDùngĐúngLexerCủaMình() {
        var lexer = PostgresAdapter.INSTANCE.createLexer(CharStreams.fromString("select 1"));
        assertTrue(lexer instanceof PostgreSQLLexer);
    }

    @Test
    void oracleDùngĐúngLexerCủaMình() {
        var lexer = OracleAdapter.INSTANCE.createLexer(CharStreams.fromString("select 1 from dual"));
        assertTrue(lexer instanceof PlSqlLexer);
    }
}
