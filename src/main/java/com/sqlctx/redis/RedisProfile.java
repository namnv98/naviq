package com.sqlctx.redis;

/**
 * 1 context Redis đặt tên. {@code readonly}: chặn ở phía client mọi lệnh ghi (cờ write trong commands.tsv)
 * và mọi lệnh lạ không có trong grammar - dùng cho context nối vào prod.
 */
public record RedisProfile(
        String name,
        String host,
        int port,
        String user,
        String password,
        int db,
        boolean tls,
        boolean readonly
) {

    public RedisProfile withDb(int newDb) {
        return new RedisProfile(name, host, port, user, password, newDb, tls, readonly);
    }

    /** Không lưu mật khẩu trong file thì lấy từ biến môi trường (REDISCLI_AUTH là biến redis-cli cũng đọc). */
    public String resolvedPassword() {
        if (password != null && !password.isEmpty()) {
            return password;
        }
        String env = System.getenv("SQLCTX_PASSWORD");
        if (env == null || env.isEmpty()) {
            env = System.getenv("REDISCLI_AUTH");
        }
        return env;
    }

    public String label() {
        return (user != null && !user.isEmpty() ? user + "@" : "") + host + ":" + port + "/" + db + (tls ? " (tls)" : "");
    }
}
