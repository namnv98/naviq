package com.sqlctx.dialect;

import com.sqlctx.schema.Dialect;

/**
 * Điểm chọn adapter DUY NHẤT theo Dialect - toàn bộ phần còn lại của app gọi qua đây, không bao giờ
 * new trực tiếp PostgresAdapter/OracleAdapter. Thêm 1 dialect mới: thêm 1 case ở đây (và 1 giá trị enum
 * mới trong {@link Dialect}) - đây là NƠI DUY NHẤT còn "switch theo dialect" trong toàn bộ app.
 */
public final class DialectAdapters {

    private DialectAdapters() {
    }

    public static DialectAdapter of(Dialect dialect) {
        return switch (dialect) {
            case POSTGRES -> PostgresAdapter.INSTANCE;
            case ORACLE -> OracleAdapter.INSTANCE;
        };
    }
}
