package com.sqlctx.support;

import com.sqlctx.sql.completion.GrammarWarmup;
import com.sqlctx.sql.schema.Dialect;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Làm nóng grammar 1 lần trước test đầu tiên của JVM (đăng ký tự động qua META-INF/services + junit-platform.properties).
 * Không có bước này thì test nào chạy đầu tiên với grammar Oracle sẽ vượt budget và nhận kết quả rỗng - test hỏng hay
 * không tuỳ thứ tự chạy.
 */
public final class GrammarWarmupExtension implements BeforeAllCallback {

    private static final AtomicBoolean done = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) {
        if (done.compareAndSet(false, true)) {
            GrammarWarmup.warm(Dialect.ORACLE);
            GrammarWarmup.warm(Dialect.POSTGRES);
        }
    }
}
