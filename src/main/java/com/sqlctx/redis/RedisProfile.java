package com.sqlctx.redis;

/**
 * 1 context Redis đặt tên. {@code readonly}: chặn ở phía client mọi lệnh ghi (cờ write trong commands.tsv)
 * và mọi lệnh lạ không có trong grammar - dùng cho context nối vào prod. {@code sentinelMaster}: khác null thì
 * host/port là của 1 sentinel, master thật do sentinel chỉ ra ({@code sentinelPassword}: mật khẩu của sentinel nếu có).
 */
public record RedisProfile(
        String name,
        String host,
        int port,
        String user,
        String password,
        int db,
        boolean tls,
        boolean readonly,
        String sentinelMaster,
        String sentinelPassword
) {

    public RedisProfile withDb(int newDb) {
        return new RedisProfile(name, host, port, user, password, newDb, tls, readonly, sentinelMaster, sentinelPassword);
    }

    /** Cùng thông tin đăng nhập, nối thẳng tới 1 node (master sentinel chỉ ra, node cluster) - không qua sentinel nữa. */
    public RedisProfile at(String newHost, int newPort) {
        return new RedisProfile(name, newHost, newPort, user, password, db, tls, readonly, null, null);
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
        return (user != null && !user.isEmpty() ? user + "@" : "") + host + ":" + port + "/" + db + (tls ? " (tls)" : "")
                + (sentinelMaster != null ? " (sentinel: " + sentinelMaster + ")" : "");
    }
}
