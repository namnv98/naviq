package com.sqlctx.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultExporterTest {

    @TempDir
    Path tmp;

    @Test
    void formatSuyRaTừĐuôiFile() {
        assertEquals("csv", ResultExporter.formatFor("/tmp/a.csv"));
        assertEquals("csv", ResultExporter.formatFor("/tmp/A.CSV"));
        assertEquals("json", ResultExporter.formatFor("/tmp/a.json"));
        assertEquals("text", ResultExporter.formatFor("/tmp/a.log"));
        assertEquals("text", ResultExporter.formatFor("/tmp/a"));
    }

    @Test
    void csvEscapeDấuPhẩyVàNgoặcKép() throws IOException {
        Path f = tmp.resolve("out.csv");
        ResultExporter.export(f, "csv", List.of("a", "b"),
                List.of(List.of("has,comma", "has\"quote")));
        assertEquals("a,b\n\"has,comma\",\"has\"\"quote\"\n", Files.readString(f));
    }

    @Test
    void csvNullSentinelThànhÔTrống() throws IOException {
        Path f = tmp.resolve("out.csv");
        ResultExporter.export(f, "csv", List.of("a"), List.of(List.of("<null>")));
        assertEquals("a\n\n", Files.readString(f));
    }

    @Test
    void csvGhiĐèChứKhôngNốiTiếp() throws IOException {
        Path f = tmp.resolve("out.csv");
        ResultExporter.export(f, "csv", List.of("a"), List.of(List.of("1")));
        ResultExporter.export(f, "csv", List.of("a"), List.of(List.of("2")));
        assertEquals("a\n2\n", Files.readString(f));
    }

    @Test
    void jsonNullSentinelThànhJsonNull() throws IOException {
        Path f = tmp.resolve("out.json");
        ResultExporter.export(f, "json", List.of("a", "b"), List.of(List.of("1", "<null>")));
        assertEquals("[\n  {\"a\":\"1\",\"b\":null}\n]\n", Files.readString(f));
    }

    @Test
    void jsonEscapeKýTựĐặcBiệt() throws IOException {
        Path f = tmp.resolve("out.json");
        ResultExporter.export(f, "json", List.of("a"), List.of(List.of("line1\nline2\ttab\"quote\\back")));
        String expected = "[\n  {\"a\":\"line1\\nline2\\ttab\\\"quote\\\\back\"}\n]\n";
        assertEquals(expected, Files.readString(f));
    }

    @Test
    void textGhiNốiTiếpQuaNhiềuLầnGọi() throws IOException {
        Path f = tmp.resolve("out.log");
        ResultExporter.export(f, "text", List.of("a"), List.of(List.of("1")));
        ResultExporter.export(f, "text", List.of("a"), List.of(List.of("2")));
        assertEquals("a\n1\n\na\n2\n\n", Files.readString(f));
    }

    @Test
    void tríchTênBảngInsert() {
        assertEquals("users", ResultExporter.extractDmlTableName("insert into users(id) values (1)"));
    }

    @Test
    void tríchTênBảngUpdate() {
        assertEquals("users", ResultExporter.extractDmlTableName("update users set name = 'a' where id = 1"));
    }

    @Test
    void tríchTênBảngDelete() {
        assertEquals("users", ResultExporter.extractDmlTableName("delete from users where id = 1"));
    }

    @Test
    void tríchTênBảngKhôngPhânBiệtHoaThường() {
        assertEquals("users", ResultExporter.extractDmlTableName("INSERT INTO users(id) VALUES (1)"));
    }

    @Test
    void tríchTênBảngCóSchemaVàQuote() {
        assertEquals("public.\"Users\"", ResultExporter.extractDmlTableName("insert into public.\"Users\"(id) values (1)"));
    }

    @Test
    void khôngPhảiDmlThìTrảVềNull() {
        assertNull(ResultExporter.extractDmlTableName("select * from users"));
    }
}
