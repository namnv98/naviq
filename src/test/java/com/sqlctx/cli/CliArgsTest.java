package com.sqlctx.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {

    @AfterEach
    void xoáProperty() {
        for (String k : List.of("CONTEXT", "HOST", "PORT", "COMMAND", "READONLY", "TLS", "LANG", "DIALECT")) {
            System.clearProperty("DB_" + k);
        }
    }

    @Test
    void cảDạngBằngLẫnDấuCách() {
        assertEquals(List.of(), CliArgs.apply(new String[]{"--context", "pg-dev", "--host=127.0.0.1", "--command", "select 1; select 2"}));
        assertEquals("pg-dev", System.getProperty("DB_CONTEXT"));
        assertEquals("127.0.0.1", System.getProperty("DB_HOST"));
        assertEquals("select 1; select 2", System.getProperty("DB_COMMAND"));
    }

    @Test
    void tuỳChọnĐúngSaiViếtTrốngLàTrue() {
        assertEquals(List.of(), CliArgs.apply(new String[]{"--readonly", "--tls", "false", "--context=x"}));
        assertEquals("true", System.getProperty("DB_READONLY"));
        assertEquals("false", System.getProperty("DB_TLS"));
        assertEquals("x", System.getProperty("DB_CONTEXT"));
    }

    @Test
    void cờKhôngNhậnGiáTrị() {
        assertEquals(List.of(), CliArgs.apply(new String[]{"--yes", "--command=x"}));
        assertEquals(1, CliArgs.apply(new String[]{"--yes=1"}).size());
    }

    @Test
    void tuỳChọnLạCóGợiÝ() {
        List<String> e = CliArgs.apply(new String[]{"--contxt=pg-dev"});
        assertEquals(1, e.size());
        assertTrue(e.get(0).contains("--context"), e.get(0));
    }

    @Test
    void thiếuGiáTrịVàChữThừaLàLỗi() {
        assertEquals(1, CliArgs.apply(new String[]{"--context"}).size());
        assertEquals(1, CliArgs.apply(new String[]{"--context", "--host=x"}).size(), "--host không phải giá trị của --context");
        assertEquals(1, CliArgs.apply(new String[]{"pg-dev"}).size());
    }
}
