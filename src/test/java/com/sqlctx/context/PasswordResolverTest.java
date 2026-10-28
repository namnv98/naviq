package com.sqlctx.context;

import com.sqlctx.sql.schema.Dialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PasswordResolverTest {

    @TempDir
    Path dir;

    private Path pgpass(String content, String perms) throws Exception {
        Path f = dir.resolve("pgpass");
        Files.writeString(f, content);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(perms));
        return f;
    }

    @Test
    void explicitThenEnvThenPgpass() throws Exception {
        Path f = pgpass("db1:5432:app:me:from-file\n", "rw-------");
        assertEquals("x", PasswordResolver.resolve(Dialect.POSTGRES, "db1", "5432", "app", "me", "x", Map.of(), f));
        assertEquals("s", PasswordResolver.resolve(Dialect.POSTGRES, "db1", "5432", "app", "me", null,
                Map.of("SQLCTX_PASSWORD", "s", "PGPASSWORD", "p"), f));
        assertEquals("p", PasswordResolver.resolve(Dialect.POSTGRES, "db1", "5432", "app", "me", "", Map.of("PGPASSWORD", "p"), f));
        assertEquals("from-file", PasswordResolver.resolve(Dialect.POSTGRES, "db1", "5432", "app", "me", null, Map.of(), f));
    }

    @Test
    void pgpassWildcardsEscapesAndFirstMatchWins() throws Exception {
        Path f = pgpass("""
                # comment
                other:*:*:*:nope
                db1:*:*:me:pa\\:ss\\\\word
                *:*:*:*:fallback
                """, "rw-------");
        assertEquals("pa:ss\\word", PasswordResolver.fromPgpass(f, "db1", "6543", "any", "me"));
        assertEquals("fallback", PasswordResolver.fromPgpass(f, "db2", "5432", "app", "you"));
    }

    @Test
    void ignoresWorldReadablePgpassAndOracleSkipsPostgresSources() throws Exception {
        Path f = pgpass("*:*:*:*:secret\n", "rw-r--r--");
        assertNull(PasswordResolver.fromPgpass(f, "h", "1", "d", "u"));
        Path ok = pgpass("*:*:*:*:secret\n", "rw-------");
        assertNull(PasswordResolver.resolve(Dialect.ORACLE, "h", "1521", "svc", "u", null, Map.of("PGPASSWORD", "p"), ok));
    }
}
