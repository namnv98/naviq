package com.sqlctx.datasource;

import com.sqlctx.schema.Dialect;

/** 1 "context" đặt tên (kiểu kubectx) - gộp đủ thông tin để nối 1 connection cụ thể. */
public record ConnectionProfile(
        String name,
        Dialect dialect,
        String host,
        String port,
        String dbname,
        String user,
        String password
) {
}
