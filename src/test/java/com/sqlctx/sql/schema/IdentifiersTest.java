package com.sqlctx.sql.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentifiersTest {

    @Test
    void postgresQuotesOnlyWhenBareNameWouldNotResolve() {
        assertEquals("users", Identifiers.quote("users", Dialect.POSTGRES));
        assertEquals("\"OrderItems\"", Identifiers.quote("OrderItems", Dialect.POSTGRES)); // gõ trần bị gấp về orderitems
        assertEquals("\"order\"", Identifiers.quote("order", Dialect.POSTGRES));           // từ khoá reserved
        assertEquals("\"my table\"", Identifiers.quote("my table", Dialect.POSTGRES));
        assertEquals("\"a\"\"b\"", Identifiers.quote("a\"b", Dialect.POSTGRES));
    }

    @Test
    void oracleQuotesReservedWithOriginalUppercaseAndKeepsMixedCase() {
        assertEquals("users", Identifiers.quote("users", Dialect.ORACLE));                  // USERS trong catalog
        assertEquals("\"LEVEL\"", Identifiers.quote("level", Dialect.ORACLE));             // reserved: quote đúng chữ hoa gốc
        assertEquals("\"OrderItems\"", Identifiers.quote("OrderItems", Dialect.ORACLE));   // tạo có quote, hoa thường lẫn
        assertEquals("\"abc\"", Identifiers.quote("\"abc\"", Dialect.ORACLE));         // tạo có quote, toàn chữ thường
    }

    @Test
    void normalizeFoldsUnquotedAndKeepsQuoted() {
        assertEquals("users", Identifiers.normalize("USERS", Dialect.POSTGRES));
        assertEquals("OrderItems", Identifiers.normalize("\"OrderItems\"", Dialect.POSTGRES));
        assertEquals("users", Identifiers.normalize("\"USERS\"", Dialect.ORACLE));
        assertEquals("OrderItems", Identifiers.normalize("\"OrderItems\"", Dialect.ORACLE));
        assertEquals("\"abc\"", Identifiers.normalize("\"abc\"", Dialect.ORACLE)); // bảng "abc", không phải ABC
        assertEquals("abc", Identifiers.normalize("abc", Dialect.ORACLE));             // không quote = bảng ABC
        assertEquals("public.OrderItems", Identifiers.normalizeQualified("PUBLIC.\"OrderItems\"", Dialect.POSTGRES));
        assertEquals("public.a.b", Identifiers.normalizeQualified("public.\"a.b\"", Dialect.POSTGRES));
    }

    @Test
    void oracleCatalogNamesFoldOnlyWhenUppercase() {
        assertEquals("users", SchemaLoader.oracleName("USERS"));
        assertEquals("OrderItems", SchemaLoader.oracleName("OrderItems"));
        assertEquals("\"abc\"", SchemaLoader.oracleName("abc")); // không được trùng bảng ABC (-> "abc")
    }
}
