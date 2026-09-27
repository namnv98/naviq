package com.sqlctx.datasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import static java.util.Objects.isNull;

public class OracleDataSource {
    private static Connection conn;

    public static void init() throws Exception {
        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String service = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");
        String pass = System.getProperty("DB_PASSWORD");

        if (host == null || port == null || service == null || user == null || pass == null) {
            throw new RuntimeException(
                    "Missing env. Required: DB_HOST, DB_PORT, DB_NAME (service name), DB_USER, DB_PASSWORD"
            );
        }

        // --ssl=true chuyển giao thức sang tcps:// (mTLS) - đúng quy ước Oracle Net (tương đương
        // PROTOCOL=TCPS trong tnsnames.ora), không phải phát minh riêng.
        boolean ssl = Boolean.parseBoolean(System.getProperty("DB_SSL", "false"));
        String protocol = ssl ? "tcps" : "tcp";
        String url = "jdbc:oracle:thin:@" + protocol + "://" + host + ":" + port + "/" + service;

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", pass);
        if (ssl) {
            applySsl(props);
        }

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
     * SSL/TLS qua Oracle wallet: --sslwallet=<thư mục chứa cwallet.sso/ewallet.p12>, tuỳ chọn
     * --sslserverdnmatch=false khi server dùng chứng chỉ tự ký không khớp CN/SAN. Tên property đúng
     * chuẩn ojdbc (oracle.net.wallet_location / oracle.net.ssl_server_dn_match), không tự bịa tên riêng.
     */
    private static void applySsl(Properties props) {
        String wallet = System.getProperty("DB_SSLWALLET");
        if (wallet != null) {
            props.setProperty("oracle.net.wallet_location",
                    "(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=" + wallet + ")))");
        }
        String dnMatch = System.getProperty("DB_SSLSERVERDNMATCH");
        if (dnMatch != null) {
            props.setProperty("oracle.net.ssl_server_dn_match", dnMatch);
        }
    }

    public static Connection get() {
        try {
            // DB bị down rồi lên lại (mất mạng, restart...) không tự làm conn == null - driver tự phát
            // hiện socket chết và đánh dấu closed nội bộ, mọi lần dùng sau đó chỉ ném lỗi "connection
            // closed" mãi mãi dù DB đã sống lại, vì object conn cũ không bao giờ được thay. isValid()
            // chủ động ping DB (bắt cả trường hợp hiếm khi socket chết nhưng isClosed() chưa kịp cập
            // nhật) - tốn 1 round-trip nhỏ mỗi câu lệnh, chấp nhận được cho 1 CLI tương tác.
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

    /** Đóng kết nối hiện tại (nếu có) rồi mở kết nối mới sang service name (PDB) khác, cùng host/user/password. */
    public static void reconnect(String serviceName) throws Exception {
        close();
        conn = null;
        System.setProperty("DB_DBNAME", serviceName);
        init();
    }
}
