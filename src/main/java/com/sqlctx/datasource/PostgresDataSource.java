package com.sqlctx.datasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import static java.util.Objects.isNull;

public class PostgresDataSource {
    private static Connection conn;

    public static void init() throws Exception {
        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String db = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");
        String pass = System.getProperty("DB_PASSWORD");

        if (host == null || port == null || db == null || user == null || pass == null) {
            throw new RuntimeException(
                    "Missing env. Required: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD"
            );
        }

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", pass);
        applySsl(props);

        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // conn cũ (nếu có) coi như đã chết sẵn - đóng thất bại cũng không sao, không chặn việc mở lại.
            }
        }
        conn = DriverManager.getConnection(url, props);
    }

    /**
     * SSL/TLS qua --sslmode=/--sslrootcert=/--sslcert=/--sslkey=/--sslpassword= - đúng tên property mà
     * pgjdbc đã định nghĩa sẵn (giống libpq), không tự bịa tên riêng. Không truyền gì thì pgjdbc tự dùng
     * mặc định "prefer" của nó - không đổi hành vi cũ nếu người dùng không chỉ định.
     */
    private static void applySsl(Properties props) {
        putIfSet(props, "sslmode", "DB_SSLMODE");
        putIfSet(props, "sslrootcert", "DB_SSLROOTCERT");
        putIfSet(props, "sslcert", "DB_SSLCERT");
        putIfSet(props, "sslkey", "DB_SSLKEY");
        putIfSet(props, "sslpassword", "DB_SSLPASSWORD");
    }

    private static void putIfSet(Properties props, String key, String sysProp) {
        String v = System.getProperty(sysProp);
        if (v != null) {
            props.setProperty(key, v);
        }
    }

    public static Connection get() {
        try {
            // DB bị down rồi lên lại (mất mạng, restart...) không tự làm conn == null - pgjdbc tự phát
            // hiện socket chết và đánh dấu closed nội bộ, mọi lần dùng sau đó chỉ ném "This connection
            // has been closed." mãi mãi dù DB đã sống lại, vì object conn cũ không bao giờ được thay.
            // isValid() chủ động ping DB (bắt cả trường hợp hiếm khi socket chết nhưng isClosed() chưa
            // kịp cập nhật) - tốn 1 round-trip nhỏ mỗi câu lệnh, chấp nhận được cho 1 CLI tương tác.
            if (isNull(conn) || conn.isClosed() || !conn.isValid(2)) {
                init();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return conn;
    }

    public static void close() throws Exception {
        if (conn != null) conn.close();
    }

    /** Đóng kết nối hiện tại (nếu có) rồi mở kết nối mới sang database khác, cùng host/user/password. */
    public static void reconnect(String dbName) throws Exception {
        close();
        conn = null;
        System.setProperty("DB_DBNAME", dbName);
        init();
    }
}