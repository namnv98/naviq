package com.sqlctx.sql.lsp;

import com.sqlctx.sql.completion.support.CompletionFixtures;
import com.sqlctx.sql.schema.Dialect;
import com.sqlctx.sql.schema.SchemaIndex;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlLanguageServerTest {

    @Test
    void slicesBySemicolonOutsideStringsAndOraclePlsqlBlocks() {
        String pg = "select ';' from a; select 2;\nselect 3";
        assertEquals(List.of(new StatementSlicer.Slice(0, 17), new StatementSlicer.Slice(18, 27), new StatementSlicer.Slice(28, 37)),
                StatementSlicer.slices(pg, Dialect.POSTGRES));

        String ora = "select 1 from dual;\nbegin\n  null;\n  null;\nend;\n/\nselect 2 from dual";
        List<StatementSlicer.Slice> slices = StatementSlicer.slices(ora, Dialect.ORACLE);
        assertEquals("select 1 from dual", ora.substring(slices.get(0).start(), slices.get(0).end()));
        assertEquals(3, slices.size(), slices.toString());
        assertTrue(ora.substring(slices.get(1).start(), slices.get(1).end()).contains("null;\n  null;"), slices.toString());
        assertEquals("select 2 from dual", ora.substring(slices.get(slices.size() - 1).start(), slices.get(slices.size() - 1).end()).strip());
    }

    @Test
    void completionUsesOnlyStatementAtCursorAndReplacesTypedPrefix() {
        CompletionFixtures.installPostgres();
        SqlLanguageServer server = new SqlLanguageServer(Dialect.POSTGRES);
        // câu trước nhắc tới orders - không được ảnh hưởng scope của câu đang gõ
        String text = "select * from orders o;\nselect u.na from users u";
        int offset = text.indexOf("u.na") + 4;

        List<CompletionItem> items = server.completionAt(text, offset).getItems();
        CompletionItem first = items.get(0);
        assertEquals("u.name", first.getLabel());
        var edit = first.getTextEdit().getLeft();
        assertEquals(new Position(1, 7), edit.getRange().getStart()); // thay cả "u.na"
        assertEquals(new Position(1, 11), edit.getRange().getEnd());
        assertTrue(items.stream().noneMatch(i -> i.getLabel().startsWith("o.")), items.toString());
    }

    @Test
    void signatureHelpMarksActiveParameter() {
        CompletionFixtures.installPostgres();
        SchemaIndex.functionSignatures = Map.of("date_trunc", "(text, timestamp) → timestamp");
        SqlLanguageServer server = new SqlLanguageServer(Dialect.POSTGRES);
        String text = "select 1;\nselect date_trunc('day', ";
        var help = server.signatureAt(text, text.length());
        assertEquals("date_trunc(text, timestamp) → timestamp", help.getSignatures().get(0).getLabel());
        assertEquals(1, help.getActiveParameter());
    }

    @Test
    void diagnosticsReportRealErrorsPerStatementOnly() {
        CompletionFixtures.installPostgres();
        SqlLanguageServer server = new SqlLanguageServer(Dialect.POSTGRES);
        String text = "select * from users wher id = 1;\nselect * from users where ";
        var diagnostics = server.diagnosticsFor(text);
        assertEquals(1, diagnostics.size(), diagnostics.toString()); // câu 2 chỉ chưa gõ xong
        assertEquals(new Position(0, 25), diagnostics.get(0).getRange().getStart());
    }

    @Test
    void formattingReplacesWholeDocumentUsingEditorIndent() {
        SqlLanguageServer server = new SqlLanguageServer(Dialect.POSTGRES);
        var options = new org.eclipse.lsp4j.FormattingOptions(2, true);
        var edits = server.formatEdits("select a from t;\nselect b from u", options);
        assertEquals(1, edits.size());
        assertEquals("SELECT\n  a\nFROM\n  t;\n\nSELECT\n  b\nFROM\n  u", edits.get(0).getNewText());
        assertEquals(new Position(1, 15), edits.get(0).getRange().getEnd());
        // đã đúng định dạng thì không gửi edit
        assertTrue(server.formatEdits(edits.get(0).getNewText(), options).isEmpty());
    }
}
