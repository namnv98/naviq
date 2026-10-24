package com.sqlctx.datasource;

import com.sqlctx.schema.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mật khẩu kết nối khi không muốn lưu plaintext trong ~/.sqlctx/connections.conf. Thứ tự: mật khẩu tường minh
 * (--password=, hoặc context có lưu) -> biến môi trường SQLCTX_PASSWORD -> (Postgres) PGPASSWORD -> (Postgres)
 * file pgpass như psql/libpq (PGPASSFILE hoặc ~/.pgpass, "host:port:db:user:password", "*" khớp mọi giá trị).
 */
public final class PasswordResolver {

    private PasswordResolver() {
    }

    /** @return null nếu không tìm thấy ở đâu */
    public static String resolve(Dialect dialect, String host, String port, String db, String user, String explicit) {
        return resolve(dialect, host, port, db, user, explicit, System.getenv(), pgpassFile());
    }

    static String resolve(Dialect dialect, String host, String port, String db, String user, String explicit,
                          Map<String, String> env, Path pgpass) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String fromEnv = env.get("SQLCTX_PASSWORD");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        if (dialect != Dialect.POSTGRES) {
            return null;
        }
        String pgPassword = env.get("PGPASSWORD");
        if (pgPassword != null && !pgPassword.isEmpty()) {
            return pgPassword;
        }
        return fromPgpass(pgpass, host, port, db, user);
    }

    private static Path pgpassFile() {
        String custom = System.getenv("PGPASSFILE");
        return custom != null && !custom.isEmpty() ? Path.of(custom) : Path.of(System.getProperty("user.home"), ".pgpass");
    }

    /** Dòng đầu tiên khớp, như libpq. File cho người khác đọc được thì bỏ qua (libpq cũng vậy) và cảnh báo. */
    static String fromPgpass(Path file, String host, String port, String db, String user) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                System.err.println("WARNING: bỏ qua " + file + " vì người khác đọc được - chạy: chmod 600 " + file);
                return null;
            }
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // không phải POSIX - không kiểm tra được quyền
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> f = splitPgpassLine(line);
                if (f.size() == 5 && matches(f.get(0), host) && matches(f.get(1), port)
                        && matches(f.get(2), db) && matches(f.get(3), user)) {
                    return f.get(4);
                }
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return null;
    }

    private static boolean matches(String pattern, String value) {
        return pattern.equals("*") || pattern.equals(value);
    }

    /** Tách theo ":" chưa escape; "\:" và "\\" là ký tự thường. */
    private static List<String> splitPgpassLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                cur.append(line.charAt(++i));
            } else if (c == ':' && fields.size() < 4) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }
}
