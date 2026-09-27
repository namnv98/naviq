package com.sqlctx.cli.command;

import com.sqlctx.cli.terminal.SqlHighlighter;
import com.sqlctx.datasource.ConnectionProfile;
import com.sqlctx.datasource.ConnectionProfileStore;
import com.sqlctx.dialect.DialectAdapters;
import com.sqlctx.schema.Dialect;
import com.sqlctx.schema.SchemaIndex;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;

import java.util.List;

import static com.sqlctx.cli.session.SqlctxSession.currentContextName;
import static com.sqlctx.cli.session.SqlctxSession.say;

/**
 * Quản lý context/kết nối kiểu kubectx: \ctx save/switch/list/-, \c (đổi database cùng server), và
 * --context=<tên> lúc khởi động. Đọc/ghi currentContextName/previousConnection qua SqlctxSession.
 */
public final class ContextManager {

    private ContextManager() {
    }

    /**
     * "--context=tên" cho 2 việc trong 1 lần gõ:
     * - Tên ĐÃ CÓ trong ~/.sqlctx/connections.conf: nạp lại, chỉ điền phần chưa được truyền tường minh trên
     *   dòng lệnh (--host=/... gõ kèm vẫn thắng nếu trùng).
     * - Tên CHƯA CÓ nhưng dòng lệnh gõ ĐỦ --host=/--port=/--dbname=/--user=/--password=: coi đây là lần
     *   đầu kết nối tới nơi này, TỰ LƯU LUÔN thành context mới - lần sau chỉ cần gõ --context=tên là đủ,
     *   khỏi phải nhớ/gõ lại toàn bộ --host=.../--password=... như lần đầu.
     */
    public static void applyContext(String name) {
        var existing = ConnectionProfileStore.find(name);
        if (existing.isPresent()) {
            ConnectionProfile p = existing.get();
            setIfAbsent("DB_DIALECT", p.dialect().name().toLowerCase());
            setIfAbsent("DB_HOST", p.host());
            setIfAbsent("DB_PORT", p.port());
            setIfAbsent("DB_DBNAME", p.dbname());
            setIfAbsent("DB_USER", p.user());
            setIfAbsent("DB_PASSWORD", p.password());
            return;
        }

        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String dbname = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");
        String password = System.getProperty("DB_PASSWORD");
        if (host == null || port == null || dbname == null || user == null || password == null) {
            throw new RuntimeException("Không có context tên \"" + name + "\", và dòng lệnh cũng chưa gõ đủ "
                    + "--host=/--port=/--dbname=/--user=/--password= để tự lưu thành context mới - "
                    + "xem \\ctx (sau khi đã nối được lần nào đó) để biết tên đã lưu.");
        }
        Dialect dialect = "oracle".equalsIgnoreCase(System.getProperty("DB_DIALECT", "postgres"))
                ? Dialect.ORACLE : Dialect.POSTGRES;
        try {
            ConnectionProfileStore.save(new ConnectionProfile(name, dialect, host, port, dbname, user, password));
            System.err.println("Đã lưu context \"" + name + "\" - lần sau chỉ cần: --context=" + name);
        } catch (Exception e) {
            System.err.println("WARNING: không lưu được context \"" + name + "\" - " + e.getMessage());
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null && value != null) {
            System.setProperty(key, value);
        }
    }

    /** "[ctx] SQL > host/db (DIALECT)" hiện ở thanh trạng thái - lấy TỪ CHÍNH tham số kết nối đang dùng,
     *  không viết cứng. Phần "[ctx] " chỉ hiện khi phiên đang gắn với 1 context đã đặt tên (đồng bộ với
     *  prompt "[ctx] dbname>" - cùng 1 thông tin, 2 chỗ nhìn thấy). */
    public static String dbLabel(Dialect dialect) {
        String ctx = currentContextName != null ? "[" + currentContextName + "] " : "";
        return ctx + "SQL > " + System.getProperty("DB_HOST") + "/" + System.getProperty("DB_DBNAME")
                + " (" + dialect + ")";
    }

    public static void switchDatabase(String newDb, Dialect dialect) throws Exception {
        DialectAdapters.of(dialect).reconnect(newDb);
        SchemaIndex.reload(dialect);
    }

    /**
     * Khác {@link #switchDatabase} ở chỗ đổi được CẢ host/port/user/password/dialect - dùng cho "\ctx",
     * nhảy sang 1 connection LƯU SẴN có thể khác hẳn server/dialect, không chỉ đổi database cùng server.
     */
    public static Dialect switchContext(ConnectionProfile p, LineReaderImpl impl) throws Exception {
        // Đóng TẤT CẢ dialect đang có adapter (không biết trước đó đang ở dialect nào) trước khi đổi -
        // duyệt qua Dialect.values() thay vì gọi tên từng DataSource cụ thể, để thêm dialect mới không
        // phải sửa lại đoạn này.
        for (Dialect d : Dialect.values()) {
            try {
                DialectAdapters.of(d).close();
            } catch (Exception ignored) {
            }
        }

        System.setProperty("DB_HOST", p.host());
        System.setProperty("DB_PORT", p.port());
        System.setProperty("DB_DBNAME", p.dbname());
        System.setProperty("DB_USER", p.user());
        System.setProperty("DB_PASSWORD", p.password());
        System.setProperty("DB_DIALECT", p.dialect().name().toLowerCase());

        DialectAdapters.of(p.dialect()).reconnect(p.dbname());
        SchemaIndex.reload(p.dialect());
        impl.setHighlighter(new SqlHighlighter(p.dialect()));
        return p.dialect();
    }

    public static void listContexts(LineReader reader) {
        List<ConnectionProfile> profiles = ConnectionProfileStore.loadAll();
        if (profiles.isEmpty()) {
            say(reader, "(chưa lưu context nào - dùng \\ctx save <tên> để lưu connection đang dùng)\n");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ConnectionProfile p : profiles) {
            boolean current = p.name().equalsIgnoreCase(currentContextName);
            sb.append(current ? "* " : "  ").append(p.name())
                    .append("  (").append(p.dialect()).append(" ").append(p.host()).append(":").append(p.port())
                    .append("/").append(p.dbname()).append(" as ").append(p.user()).append(")\n");
        }
        say(reader, sb.toString());
    }
}
